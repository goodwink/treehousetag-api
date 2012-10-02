(ns treehousetag.core
  (:use [compojure.core]
        [cheshire.core]
        [clj-time.coerce :only (to-long from-long)]
        [clojure.string :only (split)])
  (:require [clojurewerkz.neocons.rest :as nrest]
            [clojurewerkz.neocons.rest.nodes :as nn]
            [clojurewerkz.neocons.rest.relationships :as nr]
            [clojurewerkz.neocons.rest.spatial :as nsp]
            [compojure.route :as route]
            [clj-time.format :as time]))

(nrest/connect! "http://localhost:7474/db/data/")

(nr/create-index "node-type")
(nsp/add-simple-point-layer "location")

;;
;; Implementation
;;

(defn- relationship-other-id [node relationship]
  (if (= (:start relationship) (:location-uri node))
    (Long/parseLong (last (split (:end relationship) #"/")))
    (Long/parseLong (last (split (:start relationship) #"/")))))

(defn- preprocess-in [type body]
  (case type
    :child (update-in body ["birthday"] #(to-long (time/parse (time/formatters :date) %)))
    body))

(defn- preprocess-out [type body]
  (case type
    :child (update-in body [:birthday] #(time/unparse (time/formatters :date) (from-long %)))
    body))

(defn- user-json [node]
  (let [friends (map (partial relationship-other-id node) (nr/outgoing-for node :types [:friend]))
        friends-of (map (partial relationship-other-id node) (nr/incoming-for node :types [:friend]))
        children (map (partial relationship-other-id node) (nr/outgoing-for node :types [:child]))]
    (generate-string (merge {:id (:id node) :friends friends :friends-of friends-of :children children} (preprocess-out :user (:data node))))))

(defn- child-json [node]
  (let [friends (map (partial relationship-other-id node) (nr/outgoing-for node :types [:friend]))
        friends-of (map (partial relationship-other-id node) (nr/incoming-for node :types [:friend]))
        parents (map (partial relationship-other-id node) (nr/incoming-for node :types [:child]))
        interests (map (partial relationship-other-id node) (nr/outgoing-for node :types [:interest]))]
    (generate-string (merge {:id (:id node) :friends friends :friends-of friends-of :parents parents :interests interests} (preprocess-out :child (:data node))))))

(defn- place-json [node]
  (let [businesses (map (partial relationship-other-id node) (nr/outgoing-for node :types [:business]))
        interests (map (partial relationship-other-id node) (nr/outgoing-for node :types [:interest]))]
    (generate-string (merge {:id (:id node) :businesses businesses :interests interests} (preprocess-out :place (:data node))))))

(defn- business-json [node]
  (let [deals (map (partial relationship-other-id node) (nr/outgoing-for node :types [:deal]))
        interests (map (partial relationship-other-id node) (nr/outgoing-for node :types [:interest]))]
    (generate-string (merge {:id (:id node) :deals deals :interests interests} (preprocess-out :business (:data node))))))

(defn- default-json [node]
  (generate-string (merge {:id (:id node)} (preprocess-out :default (:data node)))))

(defn- create-from-body
  ([req type json-func spatial]
    (let [body (parse-string (:body req))
          item (nn/create (assoc (preprocess-in type body) :type type))]
        (nn/add-to-index item "node-type" "node-type" (name type))
        (if spatial (nsp/add-node-to-layer "location" item))
        (json-func item)))
  ([id req type json-func spatial]
    (let [body (parse-string (:body req))
          item (nn/create (assoc (preprocess-in type body) :type type))]
      (do
        (nn/add-to-index item "node-type" "node-type" (name type))
        (if spatial (nsp/add-node-to-layer "location" item))
        (nr/create {:id id} {:id (:id item)} type)
        (json-func (nn/get (:id item)))))))

(defn- attach-by-ids [type start end json-func]
  (do
    (nr/create {:id (Long/parseLong start)} {:id (Long/parseLong end)} type)
    (json-func (nn/get (Long/parseLong start)))))

;;
;; API
;;

(defroutes api
  (GET "/users/:id" [id]
    (user-json (nn/get (Long/parseLong id))))

  (POST "/users" [:as req]
    (create-from-body req :user user-json true))

  (PUT "/users/:id/friends/:friend-id" [id friend-id]
    (attach-by-ids :friend id friend-id user-json))

  (POST "/users/:id/children" [id :as req]
    (create-from-body id req :child child-json false))

  (GET "/children/:id" [id]
    (child-json (nn/get (Long/parseLong id))))

  (PUT "/children/:id/friends/:friend-id" [id friend-id]
    (attach-by-ids :friend id friend-id child-json))

  (PUT "/children/:id/interests/:interest-id" [id interest-id]
    (attach-by-ids :interest id interest-id child-json))

  (POST "/interests" [:as req]
    (create-from-body req :interest default-json false))

  (GET "/interests" []
    (map default-json (nn/find "node-type" "interest")))

  (POST "/places" [:as req]
    (create-from-body req :place place-json true))

  (PUT "/places/:id/interests/:interest-id" [id interest-id]
    (attach-by-ids :interest id interest-id place-json))

  (PUT "/places/:id/businesses/:business-id" [id business-id]
    (attach-by-ids :business id business-id place-json))

  (POST "/businesses" [:as req]
    (create-from-body req :business business-json false))

  (PUT "/businesses/:id/deals" [id :as req]
    (create-from-body id req :deal default-json false)))
