^{::clerk/visibility {:code :hide :result :hide}}
(ns config-processor
  (:require
   [clojure.test :refer [deftest is run-tests]]
   [clojure.data :refer [diff]] 
   [clojure.repl :refer [source-fn]]
   [nextjournal.clerk :as clerk]
   [viewers :as v]
   [pulumicljs.utils.pulumi :as pulumi]
   [pulumicljs.processors.config-processor :as proc] 
   ))

;; Our first steps are then to start pulling apart the old parsing mechanism and build out a testing template to run against it.
;; We don't need Pulumi yet if we do it in this manner.
;; For this example we'll use Harbor since it feels diverse enough to point to.
;;
;; As we wish to modernize the form to handle any need for a Pulumi resource definition, we must first start with
;; actually defining said resource config definition.

;; ### Resource Config 
;; This will be the resource config definition we use to test and aid in visualization:
^{::clerk/visibility {:code :show :result :hide}}
(def test-config
  {:stack [{:vault:retrieve {:app-name 'app-name
                             :app-namespace 'app-name}
            :pulumi-options {}}
           [:harbor
            {:project {}
             :pulumi-options {}}
            {:robot-account {:name '(str "kube-" app-name "-robot")
                             :namespace 'app-name
                             :level "project"
                             :permissions [{:kind "project"
                                            :namespace 'app-name
                                            :access [{:action "pull" :resource "repository"}
                                                     {:action "list" :resource "repository"}]}]}
             :pulumi-options {}}]
           {:k8s:secret {:metadata
                         {:name "harbor-creds-secrets"
                          :namespace "kube-system"
                          :annotations {"replicator.v1.mittwald.de/replicate-to" "*"}}
                         :type "kubernetes.io/dockerconfigjson"
                         :stringData {".dockerconfigjson" '(str "{\"auths\":{\""
                                                                host
                                                                "\":{\"auth\":\""
                                                                (b64e (str (-> :harbor:robot-account .-fullName) ":" (-> :harbor:robot-account .-secret)))
                                                                "\"}}}")}}
            :pulumi-options {}}]})
;;#
;; ### Functions
;;#
;; Now we should define the functions that we'll actually use to parse this template and perform the processing on it.
;; I found a neat feature in Clerk combined with Clojure's REPL ns source-fn allows us to leave the source code neatly in the 
;; expected location, but both use and document it here.
;;
;; So this is a monadic lift. A fancy way of saying this method is to operate on wrapped Pulumi output values without the function itself knowing
;; anything about Pulumi. So we can use this to handle this with Clojure or Clojurescript.
;; In short it is what unwraps and allows operating on Pulumi outputs.
^{::clerk/visibility {:code :hide :result :show}}
(clerk/code (source-fn 'proc/lift))

;; This is a monadic bind. This allows values to be moved through. It checks the value to determine if we need to unwrap it.
;; If it is plain we execute on it immediately, and if it isn't we pass it on and defer the execution.
^{::clerk/visibility {:code :hide :result :show}}
(clerk/code (source-fn 'proc/chain))

;; Now we want to have a "virtual" threading operator. It is to provide a clean interpreter to the config
;; That is to say it should feel fluid to use. Idiomatic to the Clojure experience and interacting with Pulumi outputs
;; So as an example we might want the config to have: (-> :harbor:robot-account .-fullName)
;; So firstly :harbor:robot-account would represent a Pulumi Output object. So we can't treat it as a normal object.
;; Without this our configs have no way of easily stepping through the JS object itself and interact with prior step
;; results. This interaction is consistent everywhere possible, so even on exports of the data to Pulumi to store into
;; the state file we can easily interact and retrieve.
^{::clerk/visibility {:code :hide :result :show}}
(clerk/code (source-fn 'proc/do-threading))

;; This is effectively the config interpreter. It takes a config definition and context
;; and evaluates them. The context is typically *everything* including whatever
;; secret provider you use and a retrieval (or setter) step. This way you can
;; use evaluated secrets stored in Vault for example.
;; The postwalk works from the bottom up. So if you have {:stack [:vault:retrieve {:app-name 'app-name}]}
;; Then app-name is resolved *first* and it works its way up to vault:retrieve.
;; The conditions determine whether we need to use our virtual threading mecro, our function callers (in the safe-fns)
;; or our symbol lookup for resolving plain values at the top-level of the config.
^{::clerk/visibility {:code :hide :result :show}}
(clerk/code (source-fn 'proc/resolve-template))

;; It might be helpful to include some visualization aids in the form of diagrams in the execution flow for processing
;; a config.
;; This helps illustrate how the postwalk visits each node in our config.
^{::clerk/visibility {:code :hide :result :show}}
(clerk/with-viewer v/mermaid-viewer
  "graph BT
    %% The Leaves (Step 1)
    L1(\"Leaf: 'app-name\") -->|Resolves to value| P1
    L2(\"Leaf: :harbor:robot-account\") -->|Evaluated| T1
    L3(\"Leaf: .-fullName\") -->|Evaluated| T1
    
    %% The Inner Expressions (Step 2)
    subgraph Step 2: Deepest Expressions
      T1(\"Expr: -> ... .-fullName\") -->|Result: 'robot-main'| B64
      T2(\"Expr: -> ... .-secret\") -->|Result: 'xyz123'| B64
    end

    %% The Middle Layers (Step 3)
    subgraph Step 3: Intermediate Functions
      B64(\"Expr: b64e ...\") -->|Result: 'Og=='| STR
      P1(\"Expr: str 'kube-' ...\") -->|Result: 'kube-harbor-robot'| ROBOT_MAP
    end

    %% The Outer Layers (Step 4)
    subgraph Step 4: Outer Expressions
      STR(\"Expr: str 'auths' ... \") -->|Result: JSON String| FINAL_MAP
    end

    %% The Containers (Step 5)
    subgraph Step 5: Container Reconstruction
      ROBOT_MAP{:robot-account ...} --> FINAL_STACK
      FINAL_MAP{:stringData ...} --> FINAL_STACK
    end

    FINAL_STACK[Final Vector] --> ROOT[Resulting Map]

    classDef leaf fill:#e1f5fe,stroke:#01579b
    classDef expr fill:#fff9c4,stroke:#fbc02d
    classDef container fill:#e8f5e9,stroke:#2e7d32
    
    class L1,L2,L3 leaf
    class T1,T2,B64,P1,STR expr
    class ROBOT_MAP,FINAL_MAP,FINAL_STACK container")


;; With this we can note that auto-substitution of variables with their values is occurring.
(proc/resolve-template test-config {:app-name "harbor"})

;; However, what if we wanted to convert a value that is the result of an operation step.
^{::clerk/visibility {:code :show :result :hide}}
(defn run-simulation [stack initial-values]
  (let [final-state
        (reduce (fn [{:keys [ctx results]} item]
                  (let [resolved-item (proc/resolve-template item ctx)

                        ;; In a real engine, we'd deploy here.
                        ;; In simulation, we decide what "outputs" this step created.
                        ;; We look at the definition to see if we need to mock a return value.
                        resource-outputs
                        (cond
                          (and (vector? item) (= (first item) :harbor))
                          {:fullName (pulumi/output "robot$test")
                           :secret   (pulumi/output "secret-123")}
                          :else {})
                        
                        ;; In your config, you used :harbor:robot-account
                        new-key (if (and (vector? item) (= (first item) :harbor))
                                  :harbor:robot-account
                                  nil)]

                    {:results (conj results resolved-item)
                     :ctx     (if new-key
                                (assoc ctx new-key resource-outputs)
                                ctx)}))
                ;; Initial State
                {:ctx initial-values :results []}
                stack)]

    (:results final-state)))

;; and running that produces
^{::clerk/auto-expand-results? true}
^{::clerk/visibility {:code :show :result :show}}
(run-simulation test-config {:app-name "harbor"})

;; With that we can be conclusive that at least in our simulation (using Clojure)
;; Ensuring that runtime resolution occurs as expected with the deeply nested potential of
;; Pulumi outputs is essential to making sure this is a smooth experience for the developer.
;;
;;
;; ### Testing
;; Something interesting we could do is add a unit test against the result of the simulation.

;; So after the runtime evaluation this is what the harbor config's internal state would look like:
^{::clerk/visibility {:code :show :result :hide}}
(def expected-harbor-state
  [[:stack [{:vault:retrieve {:app-name "harbor", :app-namespace "harbor"}, :pulumi-options {}}
            [:harbor
             {:project {}, :pulumi-options {}}
             {:robot-account {:name "kube-harbor-robot",
                              :namespace "harbor",
                              :level "project",
                              :permissions [{:kind "project", 
                                             :namespace "harbor", 
                                             :access [{:action "pull", :resource "repository"} 
                                                      {:action "list", :resource "repository"}]}]}, 
              :pulumi-options {}}]
            {:k8s:secret {:metadata {:name "harbor-creds-secrets", 
                                     :namespace "kube-system", 
                                     :annotations {"replicator.v1.mittwald.de/replicate-to" "*"}}, 
                          :type "kubernetes.io/dockerconfigjson", 
                          :stringData {".dockerconfigjson" "{\"auths\":{\"host\":{\"auth\":\"Og==\"}}}"}}, 
             :pulumi-options {}}]]])


;; We can very simply run now an integrity test as Pulumi will treat this in a very simple manner. The key to all of 
;; this, of course, is the evaluation behind the scenes to make writing these extremely fluid and organized.
^{::clerk/visibility {:code :show :result :hide}}
(deftest harbor-integrity-test
  (let [actual (run-simulation test-config {:app-name "harbor"})
        [only-in-expected only-in-actual common] (diff expected-harbor-state actual)]
    (is (and (nil? only-in-expected)
             (nil? only-in-actual))
        (str "Config mismatch! Diff: " {:missing only-in-expected
                                                :extra only-in-actual}))))

^{::clerk/visibility {:code :hide :result :show}}
(run-tests)
;; Now this shows 1 passed test, so we know our config processor works exactly as we want.