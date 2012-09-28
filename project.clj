(defproject treehousetag "1.0.0-SNAPSHOT"
  :description "FIXME: write description"
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [cheshire "4.0.3"]
                 [compojure "1.1.3"]
                 [clojurewerkz/neocons "1.0.2"]
                 [midje "1.4.0"]]
  :plugins [[lein-ring "0.7.1"]
            [lein-midje "2.0.0-SNAPSHOT"]]
  :ring {:handler treehousetag.core/api})
