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

(fact "GET /children/:id retrieves an existing child node"
  (request :get (str "/children/" child-id) {:id child-id} nil) => (contains [200 (contains {"type" "child" "birthday" "2010-04-21"})])
  (against-background (around :facts (let [id ((last (request :post "/users" nil {:email "test1@verify.me" :latitude 30 :longitude 25})) "id")
                                           child-id ((last (request :post (str "/users/" id "/children") {:id id} {:birthday "2010-04-21"})) "id")] ?form))))

(fact "PUT /children/:id/friends/:friend-id makes friends for children"
  (request :put (str "/users/" child-id "/friends/" friend-id) {:id id :friend-id friend-id} nil) => (contains [200 (contains {"id" child-id "friends" (contains #{friend-id})})])
  (against-background (around :facts (let [id ((last (request :post "/users" nil {:email "test1@verify.me" :latitude 30 :longitude 25})) "id")
                                           child-id ((last (request :post (str "/users/" id "/children") {:id id} {:birthday "2010-03-21"})) "id")
                                           friend-id ((last (request :post (str "/users/" id "/children") {:id id} {:birthday "2010-04-21"})) "id")] ?form))))

(fact "POST /interests creates an interest node"
  (request :post "/interests" nil {:name "bowling"}) => (contains [200 (contains {"id" pos? "type" "interest" "name" "bowling"})]))

(fact "PUT /children/:id/interests/:interest-id adds an interest to a child"
  (request :put (str "/children/" child-id "/interests/" interest-id) {:id child-id :interest-id interest-id} nil) => (contains [200 (contains {"id" child-id "interests" (contains #{interest-id})})])
  (against-background (around :facts (let [id ((last (request :post "/users" nil {:email "test1@verify.me" :latitude 30 :longitude 25})) "id")
                                           child-id ((last (request :post (str "/users/" id "/children") {:id id} {:birthday "2010-03-21"})) "id")
                                           interest-id ((last (request :post "/interests" nil {:name "bowling"})) "id")] ?form))))

(fact "GET /interests retrieves all interests"
  (request :get "/interests" nil nil) => (contains [200 (contains #{(contains {"id" id "type" "interest" "name" "bowling"})})])
  (against-background (around :facts (let [id ((last (request :post "/interests" nil {:name "bowling"})) "id")] ?form))))

(fact "POST /places creates a place node"
  (request :post "/places" nil {:name "park" :latitude 30 :longitude 25}) => (contains [200 (contains {"id" pos? "type" "place" "name" "park"})]))

(fact "PUT /places/:id/interests/:interest-id tags a place with an interest"
  (request :put (str "/places/" id "/interests/" interest-id) {:id id :interest-id interest-id} nil) => (contains [200 (contains {"id" id "interests" (contains #{interest-id})})])
  (against-background (around :facts (let [id ((last (request :post "/places" nil {:name "park" :latitude 30 :longitude 25})) "id")
                                           interest-id ((last (request :post "/interests" nil {:name "bowling"})) "id")] ?form))))

(fact "POST /businesses creates a business node"
  (request :post "/businesses" nil {:name "Tom's Tambourines"}) => (contains [200 (contains {"id" pos? "type" "business" "name" "Tom's Tambourines"})]))

(fact "PUT /places/:id/businesses/:business-id associate a place with a business"
  (request :put (str "/places/" id "/businesses/" business-id) {:id id :business-id business-id} nil) => (contains [200 (contains {"id" id "businesses" (contains #{business-id})})])
  (against-background (around :facts (let [id ((last (request :post "/places" nil {:name "park" :latitude 30 :longitude 25})) "id")
                                           business-id ((last (request :post "/businesses" nil {:name "Tom's Tambourines"})) "id")] ?form))))

(fact "POST /businesses/:id/deals creates a deal for a business"
  (request :post (str "/businesses/" id "/deals") {:id id} {:discount "10%"}) => (contains [200 (contains {"type" "deal" "discount" "10%"})])
  (against-background (around :facts (let [id ((last (request :post "/businesses" nil {:name "Tom's Tambourines"})) "id")] ?form))))
