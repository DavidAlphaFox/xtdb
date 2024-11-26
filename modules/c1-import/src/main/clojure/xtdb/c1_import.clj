(ns xtdb.c1-import
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [cognitect.transit :as transit]
            [integrant.core :as ig]
            [xtdb.api :as xt]
            [xtdb.cli :as cli]
            [xtdb.node :as xtn]
            [xtdb.util :as util])
  (:import clojure.lang.MapEntry
           java.lang.AutoCloseable
           (java.nio.file ClosedWatchServiceException Files OpenOption Path StandardOpenOption StandardWatchEventKinds WatchEvent WatchEvent$Kind)
           java.util.HashSet
           xtdb.types.ClojureForm))

(defmethod ig/prep-key :xtdb/c1-import [_ opts]
  (into {:submit-client (ig/ref ::xtn/submit-client)}
        (-> opts
            (update :export-log-path
                    (fn [path]
                      (let [conformed-path (s/conform ::util/path path)]
                        (assert (not (s/invalid? conformed-path))
                                (format "invalid export log path: '%s'" conformed-path))
                        conformed-path))))))

(def ^java.util.concurrent.ThreadFactory thread-factory
  (util/->prefix-thread-factory "c1-import"))

(defn- xform-doc [doc]
  (-> doc
      (set/rename-keys {:crux.db/id :xt/id})
      (->> (into {}
                 (map (fn [[k v]]
                        (MapEntry/create k
                                         (cond-> v
                                           (and (list? v) (= (first v) 'fn))
                                           (ClojureForm.)))))))))

(defn- submit-file! [submit-client, ^Path log-file]
  (with-open [is (Files/newInputStream log-file (into-array OpenOption #{StandardOpenOption/READ}))]
    (let [rdr (transit/reader is :json)]
      (loop []
        (when-let [[tx-status tx tx-ops] (try
                                           (transit/read rdr)
                                           (catch Throwable _))]
          (when (Thread/interrupted)
            (throw (InterruptedException.)))

          (xt/submit-tx submit-client
                        (case tx-status
                          :commit (for [[tx-op {:keys [eid doc start-valid-time end-valid-time]}] tx-ops]
                                    ;; HACK: what to do if the user has a separate :xt/id  key?
                                    (case tx-op
                                      :put [:put-docs {:into :xt_docs, :valid-from start-valid-time, :valid-to end-valid-time}
                                            (xform-doc doc)]
                                      :delete [:delete-docs {:from :xt_docs, :valid-from start-valid-time, :valid-to end-valid-time} eid]
                                      :evict [:erase-docs :xt_docs eid]))
                          :abort [[:abort]])
                        {:system-time (:xtdb.api/tx-time tx)})
          (recur))))))

(defmethod ig/init-key :xtdb/c1-import [_ {:keys [^Path export-log-path submit-client]}]
  (let [watch-svc (.newWatchService (.getFileSystem export-log-path))
        watch-key (.register export-log-path watch-svc (into-array WatchEvent$Kind [StandardWatchEventKinds/ENTRY_CREATE]))

        t (doto (.newThread thread-factory
                            (fn []
                              (let [seen-files (HashSet.)]
                                (letfn [(process-file! [^Path file]
                                          (when-not (.contains seen-files file)
                                            (.add seen-files file)
                                            (log/infof "processing '%s'..." (str file))
                                            (submit-file! submit-client file)
                                            (log/infof "processed '%s'." (str file))))]
                                  (try
                                    (run! process-file!
                                          (->> (.toList (Files/list export-log-path))
                                               (sort-by #(let [[_ idx] (re-matches #"log\.(\d+)\.transit\.json" (str (.getFileName ^Path %)))]
                                                           (Long/parseLong idx)))))
                                    (while true
                                      (doseq [^WatchEvent watch-ev (.pollEvents (.take watch-svc))
                                              :let [^Path created-file (.context watch-ev)]]
                                        (process-file! (.resolve export-log-path created-file)))
                                      (.reset watch-key))
                                    (catch InterruptedException _)
                                    (catch ClosedWatchServiceException _)
                                    (catch Throwable t
                                      (log/error t "fail")))))))
            (.start))]
    (reify AutoCloseable
      (close [_]
        (.close watch-svc)
        (.interrupt t)
        (.join t)))))

(defmethod ig/halt-key! :xtdb/c1-import [_ c1-importer]
  (util/try-close c1-importer))

;; duplicated from xtdb.cli
(defn- shutdown-hook-promise []
  (let [main-thread (Thread/currentThread)
        shutdown? (promise)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (let [shutdown-ms 10000]
                                   (deliver shutdown? true)
                                   (shutdown-agents)
                                   (.join main-thread shutdown-ms)
                                   (if (.isAlive main-thread)
                                     (do
                                       (log/warn "could not stop node cleanly after" shutdown-ms "ms, forcing exit")
                                       (.halt (Runtime/getRuntime) 1))

                                     (log/info "Node stopped."))))
                               "xtdb.shutdown-hook-thread"))
    shutdown?))

(defn -main [& args]
  (util/install-uncaught-exception-handler!)

  (let [{::cli/keys [errors help], sys-opts ::cli/node-opts} (cli/parse-args args)]
    (cond
      errors (binding [*out* *err*]
               (doseq [error errors]
                 (println error))
               (System/exit 1))

      help (println help)

      :else (with-open [_submit-client (xtn/start-node sys-opts)]
              (log/info "Importer started")
              @(shutdown-hook-promise)))

    (shutdown-agents)))
