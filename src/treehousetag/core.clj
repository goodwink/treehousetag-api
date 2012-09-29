(ns treehousetag.core
  (:use [compojure.core]
        [cheshire.core]
        [clojure.string :only (split)])
  (:require [clojurewerkz.neocons.rest :as nrest]
            [clojurewerkz.neocons.rest.nodes :as nn]
            [clojurewerkz.neocons.rest.relationships :as nr]
            [compojure.route :as route]))

(nrest/connect! "http://localhost:7474/db/data/")

(defn relationship-other-id [node relationship]
  (if (= (:start relationship) (:location-uri node))
    (last (split (:end relationship) #"/"))
    (last (split (:start relationship) #"/"))))

(defn user-json [node]
  (let [friends (map (partial relationship-other-id node) (nr/outgoing-for node :types [:friend]))
        friends-of (map (partial relationship-other-id node) (nr/incoming-for node :types [:friend]))
        children (map (partial relationship-other-id node) (nr/outgoing-for node :types [:child]))]
    (generate-string (merge {:id (:id node) :friends friends :friends-of friends-of :children children} (:data node)))))

(defn child-json [node]
  (let [friends (map (partial relationship-other-id node) (nr/outgoing-for node :types [:friend]))
        friends-of (map (partial relationship-other-id node) (nr/incoming-for node :types [:friend]))
        parents (map (partial relationship-other-id node) (nr/incoming-for node :types [:child]))]
    (generate-string (merge {:id (:id node) :friends friends :friends-of friends-of :parents parents} (:data node)))))

(defroutes api
  (GET "/users/:id" [id]
    (user-json (nn/get (Long/parseLong id))))

  (POST "/users" [:as req]
    (let [body (parse-string (slurp (:body req)))]
      (user-json (nn/create body))))

  (PUT "/users/:id/addFriend" [id :as req]
    (let [friend-id ((parse-string (slurp (:body req))) "friendId")]
      (do
        (nr/create {:id id} {:id friend-id} :friend)
        (user-json (nn/get (Long/parseLong id))))))

  (PUT "/users/:id/addChild" [id :as req]
    (let [body (parse-string (slurp (:body req)))
          child-id (:id (nn/create body))]
      (do
        (nr/create {:id id} {:id child-id} :child)
        (child-json (nn/get (Long/parseLong id))))))

  (GET "/children/:id" [id]
    (child-json (nn/get (Long/parseLong id))))

  (PUT "/children/:id/addFriend" [id :as req]
    (let [friend-id ((parse-string (slurp (:body req))) "friendId")]
      (do
        (nr/create {:id id} {:id friend-id} :friend)
        (child-json (nn/get (Long/parseLong id)))))))
