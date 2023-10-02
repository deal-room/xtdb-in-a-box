(ns config.db
  (:require
   [nrepl.server :as nrepl-server]
   [cider.nrepl :refer (cider-nrepl-handler)]
   [clojure.java.io :as io]
   [xtdb.kafka.embedded :as ek]
   [xtdb.api :as xt]))

(def url-map {:kafka "localhost"})
(def port-map {:http 9999
               :kafka 9898
               :sql 1501})
(defn make-url [key] (str (url-map key) ":" (port-map key)))
(def dir-base "data/dev/")
(def dir-map {:index (str dir-base "index-store")  
              :kafka (str dir-base "kafka/")
              :lucene (str dir-base "lucene")})
(defn start! []
  (letfn [(start-kafka! [port dir] (ek/start-embedded-kafka {:xtdb.kafka.embedded/zookeeper-data-dir (io/file dir "zk-data")
                                                             :xtdb.kafka.embedded/kafka-log-dir (io/file dir "kafka-log")
                                                             :xtdb.kafka.embedded/kafka-port port}))
          (kv-store [dir]
                    {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store
                                :db-dir      (io/file dir)
                                :sync?       true}})]
    {:kafka (start-kafka!
             (port-map :kafka)
             (dir-map :kafka))
     :xtdb (xt/start-node
            {:kafka-config {:xtdb/module 'xtdb.kafka/->kafka-config
                            :bootstrap-servers (make-url :kafka)}
             :xtdb/tx-log {:xtdb/module 'xtdb.kafka/->tx-log
                           :kafka-config :kafka-config}
             :xtdb/document-store {:xtdb/module 'xtdb.kafka/->document-store
                                   :kafka-config :kafka-config}
             :xtdb/index-store (kv-store (dir-map :index))
      ;; optional:
             :xtdb.calcite/server {:port (port-map :sql)}
             :xtdb.lucene/lucene-store {:db-dir (dir-map :lucene)}
             :xtdb.http-server/server  {:port (port-map :http)}})}))

(def nodes (atom nil))

(defn stop! []
  (.close (nodes :xtdb))
  (.close (nodes :kafka)))

(defn -main [& rest]
  (println "Started XTDB...")
  (reset! nodes (start!))
  (println "XTDB up")
  (println "Starting nREPL server...")
  (nrepl-server/start-server :port 7888 :handler cider-nrepl-handler))

;coursier launch sqlline:sqlline:1.9.0 org.apache.calcite.avatica:avatica-core:1.16.0 -M sqlline.SqlLine -- -n xtdb -p xtdb -u "jdbc:avatica:remote:url=http://localhost:1501;serialization=protobuf;timeZone=UTC" -d org.apache.calcite.avatica.remote.Driver