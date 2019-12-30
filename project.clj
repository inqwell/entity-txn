(defproject entity/entity-txn "0.1.4"
  :description "CRUD Transactions"
  :url "https://github.com/inqwell/entity-txn"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [frankiesardo/linked "1.2.9"]
                 [entity/entity-core "0.1.2"]]
  :plugins [[lein-codox "0.10.7"]]
  :codox {:output-path "codox/entity-txn"
          :source-uri "https://github.com/inqwell/entity-txn/blob/master/{filepath}#L{line}"}
  :profiles {:dev {:dependencies [[typeops "0.1.2"]
                                  [com.h2database/h2 "1.4.195"]
                                  [entity/entity-sql "0.1.1"]]}})
