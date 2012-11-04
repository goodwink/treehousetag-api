(defproject treehousetag "1.0.0-SNAPSHOT"
  :description "Treehouse Tag API Endpoints"
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [cheshire "4.0.3"]
                 [compojure "1.1.3"]
                 [clojurewerkz/neocons "1.1.0-beta1"]
                 [clj-time "0.4.4"]
                 [midje "1.4.0"]
                 [crypto-random "1.1.0"]
                 [com.lambdaworks/scrypt "1.3.3"]]
  :plugins [[lein-ring "0.7.1"]
            [lein-midje "2.0.0-SNAPSHOT"]]
  :ring {:handler treehousetag.core/main-routes})
