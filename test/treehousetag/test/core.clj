(ns treehousetag.test.core
  (:use [treehousetag.core]
        [cheshire.core]
        [midje.sweet]))

(defn request [method resource & args]
  (let [response (api {:request-method method :uri resource :body (generate-string (last args)) :params (first args)})]
    [(:status response) (parse-string (:body response))]))

(fact "POST /users creates a user node"
  (request :post "/users" nil {:email "test@verify.me" :latitude 30 :longitude 25}) => (contains [200 (contains {"id" pos? "type" "user" "email" "test@verify.me"})]))

(fact "GET /users/:id retrieves an existing user node"
  (request :get (str "/users/" id) {:id id} nil) => (contains [200 (contains {"id" id "type" "user" "email" "test@verify.me"})])
  (against-background (around :facts (let [id ((last (request :post "/users" nil {:email "test@verify.me" :latitude 30 :longitude 25})) "id")] ?form))))

(fact "PUT /users/:id/friends/:friend-id makes friends"
  (request :put (str "/users/" id "/friends/" friend-id) {:id id :friend-id friend-id} nil) => (contains [200 (contains {"id" id "friends" (contains #{friend-id})})])
  (against-background (around :facts (let [id ((last (request :post "/users" nil {:email "test1@verify.me" :latitude 30 :longitude 25})) "id")
                                           friend-id ((last (request :post "/users" nil {:email "test2@verify.me" :latitude 30 :longitude 25})) "id")] ?form))))

(fact "POST /users/:id/children makes children"
  (request :post (str "/users/" id "/children") {:id id} {:birthday "2010-04-21"}) => (contains [200 (contains {"type" "child" "birthday" "2010-04-21"})])
  (against-background (around :facts (let [id ((last (request :post "/users" nil {:email "test1@verify.me" :latitude 30 :longitude 25})) "id")] ?form))))

