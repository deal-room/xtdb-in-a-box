#!/usr/bin/env bb
(require '[babashka.process :refer [shell]])
(require '[cheshire.core :refer [parse-string]])
(require '[babashka.http-client :as http])
(defn jsonstr->edn
  "Takes a JSON string, parses it and return an EDN map where the keys are keywords"
  [jsonstr]
  (parse-string jsonstr true))
(defn query-sqlite!
  "Take a path string to a SQLite database, and runs the query returning a keywordized EDN map"
  ([db] #(query-sqlite! db %))
  ([db query] 
   (let [shell-config {:out :string}
         command ["sqlite3" "-json" db query]]
     (->> (apply shell shell-config command)
          :out
          jsonstr->edn))))
(defn submit-txs!
  "Takes a vector of transactions and submits them to the XTDB http endpoint"
  [xtdb-endpoint txs]
  (->> (http/post (str xtdb-endpoint "/_xtdb/submit-tx")
                  {:headers {:content-type "application/edn"
                             :accept "application/edn"}
                   :body (prn-str {:tx-ops txs})})
       :body
       edn/read-string))
(defn await-tx!
  "Given a transaction id, waits for the transaction to succeed (default timeout of 10 seconds)"
  [xtdb-endpoint tx-id]
  (->> (http/get (str xtdb-endpoint "/_xtdb/await-tx")
                  {:headers {:accept "application/edn"}
                   :query-params {:tx-id tx-id}})
       :body
       edn/read-string)
  )

(defn make-table []
  {:xt/id :xtdb.sql/contract-schema
   :xtdb.sql/table-name "contracts"
   :xtdb.sql/table-query '{:find [id name homeworld]
                           :where [[id :name name]
                                   [id :homeworld homeworld]]}
   :xtdb.sql/table-columns '{id :keyword
                             referenceid :varchar
                             weblink :varchar 
                             title :varchar
                             description :varchar
                             source :varchar
                             naics_code :varchar
                             due_date :timestamp}})

;; Main: Query SQL -> map to XTDB put TXs -> Submit and Wait for Transaction to Succeed 
(let [xtdb-endpoint "http://localhost:9999"
      SQL! (query-sqlite! "my_database.sqlite")
      submitted-txs (->> (SQL!  "SELECT * FROM contracts;")
                         (map #(assoc % :details (jsonstr->edn (% :details))))
                         (mapv (fn [obj] [:xtdb.api/put (-> obj
                                                            (dissoc :id)
                                                            (assoc :xt/id (obj :id)))]))
                         (submit-txs! xtdb-endpoint))
      tx-id (submitted-txs :xtdb.api/tx-id)]
  (print (await-tx! xtdb-endpoint tx-id)))

