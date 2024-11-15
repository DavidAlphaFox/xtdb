(ns xtdb.log.memory-log
  (:require [xtdb.api :as xt]
            [xtdb.log :as log]
            [xtdb.node :as xtn]
            [xtdb.serde :as serde])
  (:import java.time.InstantSource
           java.time.temporal.ChronoUnit
           java.util.concurrent.CompletableFuture
           (xtdb.api Xtdb$Config)
           (xtdb.api.log FileListCache Log TxLog$Record Logs Logs$InMemoryLogFactory)
           xtdb.log.INotifyingSubscriberHandler))

(deftype InMemoryLog [!records, ^INotifyingSubscriberHandler subscriber-handler, ^InstantSource instant-src
                      ^FileListCache file-list-cache]
  Log
  (appendTx [_ record]
    (CompletableFuture/completedFuture
     (let [^TxLog$Record record (-> (swap! !records (fn [records]
                                                      (let [system-time (-> (.instant instant-src) (.truncatedTo ChronoUnit/MICROS))]
                                                        (conj records (TxLog$Record. (serde/->TxKey (count records) system-time) record)))))
                                peek)
           tx-key (.getTxKey record)]
       (.notifyTx subscriber-handler tx-key)
       (.getTxId tx-key))))

  (readTxs [_ after-tx-id limit]
    (let [records @!records
          offset (if after-tx-id
                   (inc ^long after-tx-id)
                   0)]
      (subvec records offset (min (+ offset limit) (count records)))))

  (subscribeTxs [this after-tx-id subscriber]
    (.subscribe subscriber-handler this after-tx-id subscriber))

  (appendFileNotification [_ n] (.appendFileNotification file-list-cache n))
  (subscribeFileNotifications [_ subscriber] (.subscribeFileNotifications file-list-cache subscriber)))

(defmethod xtn/apply-config! :xtdb.log/memory-log [^Xtdb$Config config _ {:keys [instant-src]}]
  (doto config
    (.setTxLog (cond-> (Logs/inMemoryLog)
                 instant-src (.instantSource instant-src)))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn open-log [^Logs$InMemoryLogFactory factory]
  (InMemoryLog. (atom []) (log/->notifying-subscriber-handler nil) (.getInstantSource factory) FileListCache/SOLO))
