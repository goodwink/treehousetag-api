(ns treehousetag.core
  (:use [compojure.core]
        [cheshire.core]
        [clj-time.coerce :only (to-long from-long)]
        [clojure.string :only (split)])
  (:require [clojurewerkz.neocons.rest :as nrest]
            [clojurewerkz.neocons.rest.records :as nrec]
            [clojurewerkz.neocons.rest.nodes :as nn]
            [clojurewerkz.neocons.rest.relationships :as nr]
            [clojurewerkz.neocons.rest.spatial :as nsp]
            [clojurewerkz.neocons.rest.cypher :as cypher]
            [clojurewerkz.neocons.rest.helpers :as nhelper]
            [compojure.route :as route]
            [compojure.handler :as handler]
            [clj-time.format :as time]
            [crypto.random :as random]
            [taoensso.carmine :as redis])
  (:import (com.lambdaworks.crypto SCryptUtil)))

(defmacro dbg [body]
  `(let [x# ~body]
     (println "dbg:" '~body "=" x#)
     x#))

(nrest/connect! "http://localhost:7474/db/data/")

(def pool (redis/make-conn-pool :max-active 50))
(def env (System/getenv))

; Set up defaults for Redis connection parameters, but allow them to be
; overridden by values in environment variables
(def redis-host (or (get env "REDIS_HOST") "127.0.0.1"))
(def redis-port (or (get env "REDIS_PORT") 6379))
(def redis-timeout (or (get env "REDIS_TIMEOUT") 4000))
(def redis-db (or (get env "REDIS_DB") 0))

(def server-spec (redis/make-conn-spec
                    :host     redis-host
                    :port     redis-port
                    :timeout  redis-timeout
                    :db       redis-db))

(defmacro redis
  [& body] `(redis/with-conn pool server-spec ~@body))

;(nn/create-index "node-type")
;(nn/create-index "email" {:unique true})
;(nsp/add-simple-point-layer "location")
;(nn/create-index "location" {:provider "spatial"})

;;
;; Implementation
;;

(defn- coerce-numeric [value]
  (if (number? value) value (Long/parseLong value)))

(defn- relationship-other-id [node relationship]
  (if (= (:start relationship) (:location-uri node))
    (Long/parseLong (last (split (:end relationship) #"/")))
    (Long/parseLong (last (split (:start relationship) #"/")))))

(def date-formatter (time/formatter "MM/dd/yyyy"))

(defn- preprocess-in [type body]
  (case type
    :child (update-in (dissoc body :interests) ["birthday"] #(to-long (time/parse date-formatter %)))
    :user (update-in body ["password"] #(SCryptUtil/scrypt (str "1b4ea9d4b23595a3853b1c69e389d3e9" %) 65536 8 1))
    body))

(defn- preprocess-out [type body]
  (case type
    :child (update-in body [:birthday] #(time/unparse (time/formatters :date) (from-long %)))
    :user (dissoc body :password)
    body))

(defn- user-map [node]
  (let [friends (map (partial relationship-other-id node) (nr/outgoing-for node :types [:friend]))
        friends-of (map (partial relationship-other-id node) (nr/incoming-for node :types [:friend]))
        children (map (partial relationship-other-id node) (nr/outgoing-for node :types [:child]))]
    (merge {:id (:id node) :friends friends :friends-of friends-of :children children} (preprocess-out :user (:data node)))))

(defn- user-json [node]
  (generate-string (user-map node)))

(defn- child-json [node]
  (let [friends (map (partial relationship-other-id node) (nr/outgoing-for node :types [:friend]))
        friends-of (map (partial relationship-other-id node) (nr/incoming-for node :types [:friend]))
        parents (map (partial relationship-other-id node) (nr/incoming-for node :types [:child]))
        interests (map (partial relationship-other-id node) (nr/outgoing-for node :types [:interest]))]
    (generate-string (merge {:id (:id node) :friends friends :friends-of friends-of :parents parents :interests interests} (preprocess-out :child (:data node))))))

(defn- place-map [node]
  (let [businesses (map (partial relationship-other-id node) (nr/outgoing-for node :types [:business]))
        interests (map (partial relationship-other-id node) (nr/outgoing-for node :types [:interest]))]
    (merge {:id (:id node) :businesses businesses :interests interests} (preprocess-out :place (:data node)))))

(defn- place-json [node]
  (generate-string (place-map node)))

(defn- event-map [node]
  (let [invitations (map (partial relationship-other-id node) (nr/outgoing-for node :types [:event]))
        owners (map (partial relationship-other-id node) (nr/incoming-for node :types [:event]))
        interests (map (partial relationship-other-id node) (nr/outgoing-for node :types [:interest]))]
    (merge {:id (:id node) :invitations invitations :owners owners :interests interests} (preprocess-out :event (:data node)))))

(defn- event-json [node]
  (generate-string (event-map node)))

(defn- recommendation-map [node]
  (let [invitations (map (partial relationship-other-id node) (nr/outgoing-for node :types [:event]))
        owners (map (partial relationship-other-id node) (nr/incoming-for node :types [:event]))
        businesses (map (partial relationship-other-id node) (nr/outgoing-for node :types [:business]))
        interests (map (partial relationship-other-id node) (nr/outgoing-for node :types [:interest]))]
    (merge {:id (:id node) :invitations invitations :owners owners :businesses businesses :interests interests} (preprocess-out :recommendation (:data node)))))

(defn- recommendation-json [node]
  (generate-string (place-map node)))

(defn- business-json [node]
  (let [deals (map (partial relationship-other-id node) (nr/outgoing-for node :types [:deal]))
        interests (map (partial relationship-other-id node) (nr/outgoing-for node :types [:interest]))]
    (generate-string (merge {:id (:id node) :deals deals :interests interests} (preprocess-out :business (:data node))))))

(defn- default-json [node]
  (if (sequential? node)
    (generate-string (map #(merge {:id (:id %)} (preprocess-out :default (:data %))) node))
    (generate-string (merge {:id (:id node)} (preprocess-out :default (:data node))))))

(defn- attach-by-ids [type start end json-func]
  (do
    (nr/maybe-create {:id (coerce-numeric start)} {:id (coerce-numeric end)} type)
    (json-func (nn/get (coerce-numeric start)))))

(defn- create-from-body
  ([req type json-func spatial]
    (let [body (parse-string (:body req))
          item (nn/create (assoc (preprocess-in type body) :type type))]
      (nn/add-to-index item "node-type" "node-type" (name type))
      (if spatial (nsp/add-node-to-layer "location" item))
      (if (= type :child) (doseq [interest (:interests body)] (attach-by-ids :interest (:id item) interest json-func)))
      (json-func item)))
  ([id req type json-func spatial]
    (let [body (parse-string (:body req))
          item (nn/create (assoc (preprocess-in type body) :type type))]
      (nn/add-to-index item "node-type" "node-type" (name type))
      (if spatial (nsp/add-node-to-layer "location" item))
      (nr/create {:id id} {:id (:id item)} type)
      (json-func (nn/get (:id item))))))

(defn- add-or-invite-friend [req]
  (let [body (dbg (parse-string (:body req)))
        user-id (:current-user-id req)
        first-name (body "firstName")
        email (body "email")
        node (first (nn/find "email" "email" email))
        friend (:data node)]
    (if (not (nil? friend))
      (do
        (attach-by-ids :friend user-id (:id node) user-json)
        (user-json node))
      (generate-string {:status "sent"})))) ;FIXME: Send email invitation

(defn- recommendations [principal distance]
  (let [loc (str
              "withinDistance:["
              (:latitude (:data principal)) ", "
              (:longitude (:data principal)) ", "
              (Double/parseDouble distance) "]")]
    (generate-string
      (map
        #(hash-map :childId (nhelper/extract-id (:self (get % "child"))) :recommendation (recommendation-map (nrec/instantiate-node-from (get % "recommendation"))))
        (cypher/tquery
          (str
            "START recommendation=node:location({loc}), principal=node({pid}) "
            "MATCH (principal)-[:child]->(child)-[:interest]->()<-[:interest]-(recommendation) "
            "WHERE recommendation.public? = true "
            "RETURN child, recommendation")
          {:loc loc :pid (:id principal)})))))

(defn- invited-events [principal]
  (generate-string
    (map
      #(hash-map :childId (nhelper/extract-id (:self (get % "child"))) :event (event-map (nrec/instantiate-node-from (get % "event"))))
      (cypher/tquery
        (str
          "START principal=node({pid}) "
          "MATCH (principal)-[:child]->(child)<-[invitation:event]-(event) "
          "WHERE invitation.rsvp? = false "
          "RETURN child, event")
        {:pid (:id principal)}))))

(defn- accepted-events [principal]
  (generate-string
    (map
      #(hash-map :childId (nhelper/extract-id (:self (get % "child"))) :event (event-map (nrec/instantiate-node-from (get % "event"))))
      (cypher/tquery
        (str
          "START principal=node({pid}) "
          "MATCH (principal)-[:child]->(child)<-[invitation:event]-(event) "
          "WHERE invitation.rsvp! = true "
          "RETURN child, event")
        {:pid (:id principal)}))))

(defn- rsvp [principal-id event-id rsvp]
  (generate-string
    (map
      #(hash-map :childId (nhelper/extract-id (:self (get % "child"))) :event (event-map (nrec/instantiate-node-from (get % "event"))))
      (cypher/tquery
        (str
          "START principal=node({pid}), event=node({eid}) "
          "MATCH (principal)-[:child]->(child)<-[invitation:event]-(event) "
          "SET invitation.rsvp = {rsvp} "
          "RETURN child, event")
        {:pid principal-id :eid event-id :rsvp rsvp}))))

(defn- friends [principal-id]
  (generate-string
    (map
      #(user-map (nrec/instantiate-node-from (get % "friend")))
      (cypher/tquery
        (str
          "START principal=node({pid}) "
          "MATCH (principal)-[:friend]->(friend) "
          "RETURN friend")
        {:pid principal-id}))))

(defn- suggested-friends [principal-id]
  (generate-string
    (map
      #(user-map (nrec/instantiate-node-from (get % "suggestion")))
      (cypher/tquery
        (str
          "START principal=node({pid}) "
          "MATCH "
          "  (principal)<-[:friend]-(suggestion), "
          "  (principal)-[r?:friend]->(suggestion) "
          "WHERE r is null "
          "RETURN suggestion")
        {:pid principal-id}))))

(defn- authenticate [req]
  (let [body (parse-string (:body req))
        email (get body "email")
        password (str "1b4ea9d4b23595a3853b1c69e389d3e9" (get body "password"))
        node (first (nn/find "email" "email" email))
        user (:data node)]
    (if (and (not (nil? user)) (SCryptUtil/check password (:password user)))
      (:id node)
      nil)))

(defn- can-read [principal-id item-id]
  (or
    (= principal-id item-id)
    (>
      (get
        (first
          (cypher/tquery
            (str
              "START principal=node({pid}), item=node({iid}) "
              "MATCH (item)<-[c?:child]-(principal)<-[f?:friend]-(item) "
              "WHERE c IS NOT null OR f IS NOT null "
              "RETURN count(coalesce(c, f)) AS num")
            {:pid principal-id :iid item-id})) "num")
      0)))

(defn- can-mutate [principal-id item-id]
  (or
    (= principal-id item-id)
    (>
      (get
        (first
          (cypher/tquery
            (str
              "START principal=node({pid}), item=node({iid}) "
              "MATCH (principal)-[c:child]->(item) "
              "RETURN count(c) AS num")
            {:pid principal-id :iid item-id})) "num")
      0)))

(defn- authorize [{method :request-method current-user-id :current-user-id} id func & args]
  (let [auth-func (if (= method :get) can-read can-mutate)]
    (if (auth-func current-user-id (Integer. id))
      (apply func args)
      {:status 403})))

;;
;; API
;;

(defroutes api-routes
  (GET "/users/:id" [id :as req]
    (authorize req id user-json (nn/get (coerce-numeric id))))

  (PUT "/users/:id/friends/:friend-id" [id friend-id :as req]
    (authorize req id attach-by-ids :friend id friend-id user-json))

  (POST "/children" [:as req]
    (authorize req (:current-user-id req) create-from-body (:current-user-id req) req :child child-json false))

  (POST "/friends" [:as req]
    (authorize req (:current-user-id req) add-or-invite-friend req))

  (GET "/friends" [suggested :as req]
    (authorize req (:current-user-id req) (if suggested suggested-friends friends) (:current-user-id req)))

  (GET "/children/:id" [id :as req]
    (authorize req id child-json (nn/get (coerce-numeric id))))

  (PUT "/children/:id/friends/:friend-id" [id friend-id :as req]
    (authorize req id attach-by-ids :friend id friend-id child-json))

  (PUT "/children/:id/interests/:interest-id" [id interest-id :as req]
    (authorize req id attach-by-ids :interest id interest-id child-json))

  (POST "/events" [:as req]
    (authorize req (:current-user-id req) create-from-body (:current-user-id req) req :event event-json true))

  (GET "/events" [accepted :as req]
    (if accepted
      (accepted-events (nn/get (:current-user-id req)))
      (invited-events (nn/get (:current-user-id req)))))

  (PUT "/events/:id" [id :as req]
    (rsvp (:current-user-id req) (coerce-numeric id) (get (parse-string (:body req)) "rsvp")))

  (POST "/interests" [:as req]
    (create-from-body req :interest default-json false))

  (POST "/places" [:as req]
    (create-from-body req :place place-json true))

  (PUT "/places/:id/interests/:interest-id" [id interest-id]
    (attach-by-ids :interest id interest-id place-json))

  (PUT "/places/:id/businesses/:business-id" [id business-id]
    (attach-by-ids :business id business-id place-json))

  (POST "/businesses" [:as req]
    (create-from-body req :business business-json false))

  (POST "/businesses/:id/deals" [id :as req]
    (create-from-body id req :deal default-json false))

  (GET "/recommendations" [distance :as req]
    (recommendations (nn/get (:current-user-id req)) (or distance "40.0"))))

(defn authentication-middleware [app]
  (fn [req]
    (let [id (coerce-numeric (redis (redis/get ((:headers req) "x-auth-token"))))]
      (if (not (nil? id))
        (app (assoc req :current-user-id id))
        {:status 401}))))

(defroutes main-routes
  (POST "/users" [:as req]
    (let [body (preprocess-in :user (parse-string (:body req)))
          user (nn/create-unique-in-index "email" "email" (get body "email") (assoc body :type :user))]
      (nn/add-to-index user "node-type" "node-type" (name :user))
      (nsp/add-node-to-layer "location" user)
      (user-json user)))

  (GET "/interests" []
    (default-json (nn/find "node-type" "node-type" "interest")))

  (POST "/sessions" [:as req]
    (let [user-id (authenticate req)]
      (if (not (nil? user-id))
        (let [token (random/base64 64)]
          (redis
            (redis/set token (str user-id)))
          (generate-string {:id user-id :token token}))
        {:status 401})))

  (-> api-routes authentication-middleware))

(defn- slurp-nonstring [obj]
  (if (string? obj)
    obj
    (slurp obj)))

(defn slurper [app]
  (fn [req]
    (app (update-in req [:body] slurp-nonstring))))

(def application
  (-> (-> main-routes slurper) handler/api))
