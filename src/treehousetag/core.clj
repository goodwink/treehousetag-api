(ns treehousetag.core
  (:use [compojure.core]
        [cheshire.core]
        [clojure.string :only (split)])
  (:require [clojurewerkz.neocons.rest :as nrest]
            [clojurewerkz.neocons.rest.nodes :as nn]
            [clojurewerkz.neocons.rest.relationships :as nr]
            [clojurewerkz.neocons.rest.spatial :as nsp]
            [compojure.route :as route]))

(nrest/connect! "http://localhost:7474/db/data/")

(nr/create-index "node-type")
(nsp/add-simple-point-layer "location")

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
        parents (map (partial relationship-other-id node) (nr/incoming-for node :types [:child]))
        interests (map (partial relationship-other-id node) (nr/outgoing-for node :types [:interest]))]
    (generate-string (merge {:id (:id node) :friends friends :friends-of friends-of :parents parents :interests interests} (:data node)))))

(defn activity-json [node]
  (generate-string (merge {:id (:id node)} (:data node))))

(defroutes api
  (GET "/users/:id" [id]
    (user-json (nn/get (Long/parseLong id))))

  (POST "/users" [:as req]
    (let [body (parse-string (slurp (:body req)))
          user (nn/create (assoc body :type :user))]
      (nr/add-to-index user "node-type" "node-type" "user")
      (nsp/add-node-to-layer "location" user)
      (user-json user)))

  (PUT "/users/:id/friends/:friend-id" [id friend-id]
    (do
      (nr/create {:id id} {:id friend-id} :friend)
      (user-json (nn/get (Long/parseLong id)))))

  (POST "/users/:id/children" [id :as req]
    (let [body (parse-string (slurp (:body req)))
          child-id (:id (nn/create (assoc body :type :child)))]
      (do
        (nr/create {:id id} {:id child-id} :child)
        (child-json (nn/get (Long/parseLong id))))))

  (GET "/children/:id" [id]
    (child-json (nn/get (Long/parseLong id))))

  (PUT "/children/:id/friends/:friend-id" [id friend-id]
    (do
      (nr/create {:id id} {:id friend-id} :friend)
      (child-json (nn/get (Long/parseLong id)))))

  (PUT "/children/:id/interests/:interest-id" [id interest-id]
    (do
      (nr/create {:id id} {:id interest-id} :interest)
      (child-json (nn/get (Long/parseLong id)))))

  (POST "/interests" [:as req]
    (let [body (parse-string (slurp (:body req)))
          interest (nn/create (assoc body :type :interest))]
      (nr/add-to-index interest "node-type" "node-type" "interest")
      (interest-json interest)))

  (GET "/interests" []
    (map interest-json (nn/find "node-type" "interest")))

  (POST "/activities" []
    (let [body (parse-string (slurp (:body req)))
          activity (nn/create (assoc body :type :activity))]
      (nr/add-to-index activity "node-type" "node-type" "activity")
      (nsp/add-node-to-layer "location" user)
      (activity-json activity))))
