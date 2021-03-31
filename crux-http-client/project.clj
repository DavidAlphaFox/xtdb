(defproject juxt/crux-http-client "crux-git-version-beta"
  :description "Crux HTTP Client"
  :url "https://github.com/juxt/crux"
  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [juxt/crux-core "crux-git-version-beta"]
                 [clj-http "3.12.1"]
                 [com.nimbusds/nimbus-jose-jwt "9.7"]]

  :profiles {:dev {:dependencies [[ch.qos.logback/logback-classic "1.2.3"]]}
             :test {:dependencies [[juxt/crux-test "crux-git-version"]
                                   [juxt/crux-http-server "crux-git-version-alpha"]

                                   ;; dependency conflicts
                                   [commons-codec "1.15"]]}}
  :middleware [leiningen.project-version/middleware]
  :pedantic? :warn)
