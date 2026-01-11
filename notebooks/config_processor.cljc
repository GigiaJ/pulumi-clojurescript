^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(ns config-processor
  (:require
   [clojure.test :refer [deftest is run-tests]]
   [clojure.data :refer [diff]]
   [clojure.repl :refer [source-fn]]
   [nextjournal.clerk :as clerk]
   [viewers :as v]
   [pulumicljs.utils.pulumi :as pulumi]
   [pulumicljs.utils.general :refer [deep-merge p->]]

   [pulumicljs.processors.config-processor :as proc]
   [general :refer [provider-template-registry]]))

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
;; ### pulumi namespace
;;#
;; The next couple methods live inside the Pulumi namespace in the library, but are best described where their usage is
;; most relevant.
;;#
;;
;; Now we should define the functions that we'll actually use to parse this template and perform the processing on it.
;; I found a neat feature in Clerk combined with Clojure's REPL ns source-fn allows us to leave the source code neatly in the 
;; expected location, but both use and document it here.
;;
;; So this is a monadic lift. A fancy way of saying this method is to operate on wrapped Pulumi output values without the function itself knowing
;; anything about Pulumi. So we can use this to handle this with Clojure or Clojurescript.
;; In short it is what unwraps and allows operating on Pulumi outputs.
^{::clerk/visibility {:code :hide :result :show}}
(clerk/code (source-fn 'pulumicljs.utils.general/p-lift))

;; This is a monadic bind. This allows values to be moved through. It checks the value to determine if we need to unwrap it.
;; If it is plain we execute on it immediately, and if it isn't we pass it on and defer the execution.
^{::clerk/visibility {:code :hide :result :show}}
(clerk/code (source-fn 'pulumicljs.utils.general/p-chain))

;; Now we want to have a "virtual" threading operator. It is to provide a clean interpreter to the config
;; That is to say it should feel fluid to use. Idiomatic to the Clojure experience and interacting with Pulumi outputs
;; So as an example we might want the config to have: (-> :harbor:robot-account .-fullName)
;; So firstly :harbor:robot-account would represent a Pulumi Output object. So we can't treat it as a normal object.
;; Without this our configs have no way of easily stepping through the JS object itself and interact with prior step
;; results. This interaction is consistent everywhere possible, so even on exports of the data to Pulumi to store into
;; the state file we can easily interact and retrieve.
^{::clerk/visibility {:code :hide :result :show}}
(clerk/code (source-fn 'pulumicljs.utils.general/p->))

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
^{::clerk/visibility {:code :show :result :show}
  ::clerk/auto-expand-results? true}
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
;; Now this shows 1 passed test, so we know our config parsing works exactly as we want.

;; Next we actually need to *process* the config that we have parsed
;; Now because way may have Pulumi outputs for previous whose values we want to use within the config
;; we need to resolve their values asynchronously

;; Important to note, but there aren't great ways around the clj->js and js->clj as they are in the core
;; namespace for Clojurescript... so we have implemented those in our user.clj so they can be processed correctly.
;; The downside is any editor will flag these as errors.
;; It may prove worthwhile to try to see the about creating the path with the least friction then.
;; Seeing as Pulumi is the ONLY part that needs the values as JS objects... we can instead try to ensure
;; Everything FROM Pulumi is returned in Clojurescript and every call TO Pulumi is auto-converted to JS
^{::clerk/visibility {:code :show :result :hide}}
(defn generic-transform
  "Takes a creator function and executes it with resolved arguments,
   handling asynchronicity when secrets are present."
  [creator-fn opts base-values options]
  (pulumi/apply-output options
                       #(creator-fn (deep-merge base-values (proc/resolve-template opts %)))))

(generic-transform (fn [args] args)  {:app-name "cats"} {:silly-hands {}} {:provider-template:storage-class {}})

;; We need to define how an individual resource (the individual resources that comprise this resource's steps) 
;; in the Resource Config Definition
;; in order to actually provide some dynamic options later, we'll use a multi-method that handles the registered
;; components specs.
(defmulti deploy-resource
  "Generic resource deployment multimethod.
  Dispatches on the fully-qualified resource keyword.
  Returns a map of {:resource (the-pulumi-resource) :common-opts-update {map-of-new-state}}."
  (fn [dispatch-key _config] dispatch-key))

;; We'll define our component specs here with just a single provider for testing config execution
^{::clerk/visibility {:code :show :result :show}
  ::clerk/auto-expand-results? true}
(def component-specs provider-template-registry)

;; We should also make a simpler test-config for now until we finish our provider design
(def test-config-2 {:app-name "test"
                    :stack [{:provider-template:storage-class {:app-name 'app-name
                                                               :app-namespace 'app-name
                                                               :random-setting "something"}
                             :pulumi-options {:safe {}}}]})

;; and the deploy-resource default function here
;; now it should be able to simply pick out the keys from the config itself that has been parsed earlier
;; and shape them into a form that can be passed to the constructor in the component spec for Pulumi
;; to generate the given resource

;; Defining a helper debug atom for debugging in real-time w/ Clerk
(defonce debug-log (atom []))
(reset! debug-log [])


;; Starting the conversion of the old deploy resource function. Since the actual process is convoluted
;; We'll instead start with the higher-order functions before this.
;; Including sorting our solution for provider organization and loading.
(defmethod deploy-resource :default
  [dispatch-key full-config]
  #_(if-let [spec (get component-specs dispatch-key)]
      (let [app-name       (:app-name full-config)
            dependsOn      (:dependsOn full-config)
            provider-key   (:provider-key spec)
            provider       (get full-config provider-key)
            resource-class (:constructor spec)

            opts-key       (keyword (str (name dispatch-key) "-opts"))


            component-opts (get full-config opts-key)

            env            {:options full-config :secrets (:secrets full-config) :component-opts component-opts}

            raw-defaults   (when-let [df (:default-fn spec)] (df env))]

        (if resource-class
          (let [base-creator (fn [final-args suffix]
                               (let [final-name (if suffix
                                                  (str app-name "-" suffix)
                                                  app-name)]
                                 (pulumi/new-resource resource-class
                                                      final-name
                                                      final-args
                                                      {provider
                                                       dependsOn})))]
            {:resource
             (p-> raw-defaults
                  #(let [defaults-list (if (vector? %)
                                         %
                                         [%])
                         is-multi?     (vector? %) resources
                         (doall
                          (map-indexed
                           (fn [idx item]
                             (let [suffix (cond
                                            (:_suffix item) (:_suffix item)
                                            is-multi? (str idx)
                                            :else nil)
                                   clean-item (dissoc item :_suffix)
                                   item-creator (fn [resolved-args]
                                                  (base-creator resolved-args suffix))
                                   _ (println item-creator)]

                               (generic-transform item-creator
                                                  component-opts
                                                  clean-item
                                                  full-config)))
                           defaults-list))]
                     (if is-multi? resources (first resources))))})

          (throw (ex-info (str "No :constructor found for spec: " dispatch-key) {:dispatch-key dispatch-key
                                                                                 :type :missing-constructor}))))

      (throw (ex-info (str "Unknown resource: " dispatch-key) {:dispatch-key dispatch-key}))))


;; Simple little helper function
;; Just using it to merge to the inner of the resource step map and associate
;; depends on to pulumi options
(defn merge-into-stack-entry
  [obj target-key merge-path merge-map]
  (let [stack (:stack obj)
        idx   (some (fn [[i m]]
                      (when (contains? m target-key)
                        i))
                    (map-indexed vector stack))]
    (if (nil? idx)
      obj
      (update-in obj (into [:stack idx] merge-path)
                 merge merge-map))))

;; Simplify the merging...
;; However, in doing this we still fail to address the actual uniqueness where duplicates
;; can cause issue (since depends-on seems too simplistic)
;; Certainly might be tired though, so I'll revisit this also in the morning.
(defn handle-keyword-item [last-resource dispatch-key final-config common-opts]
  (let [deploy-config (merge-into-stack-entry
                       final-config
                       :provider-template:storage-class
                       [:pulumi-options]
                       {:depends-on (cond-> []
                                      last-resource (conj last-resource)
                                      (:depends-on final-config) (into (:depends-on final-config)))})


        result-map    (deploy-resource dispatch-key deploy-config)
        resource      (:resource result-map)]

    [resource
     nil
     (merge common-opts (:common-opts-update result-map))]))


;; Have not touched this yet.
(defn handle-list-item [last-resource item config common-opts]
  (let [provider-key (first item)
        resource-keys (rest item)

        nested-result
        (reduce
         (fn [nested-acc resource-key]
           (let [inner-last-resource (get nested-acc :last-resource)
                 inner-resources-map (get nested-acc :resources)
                 inner-common-opts   (get nested-acc :common-opts)
                 dispatch-key (keyword (str (name provider-key) ":" (name resource-key)))
                 [new-resource new-resource-map new-common-opts]
                 (handle-keyword-item inner-last-resource dispatch-key config inner-common-opts)]
             {:last-resource (or new-resource inner-last-resource)
              :resources     (merge inner-resources-map new-resource-map)
              :common-opts   new-common-opts}))
         {:last-resource last-resource
          :resources     {}
          :common-opts   common-opts}

         resource-keys)]

    [(:last-resource nested-result)
     (:resources nested-result)
     (:common-opts nested-result)]))


;; This made me start thinking about using the Clojure feature 'spec'
;; I'll probably revisit a lot of this and better define using spec
(defn derive-identity [type-kw config resources-map]
  (let [user-alias (:alias config)
        resource-name (or (:name config) (get-in config [:metadata :name]))
        default-key type-kw]

    (cond
      user-alias user-alias
      (contains? resources-map default-key)
      (if resource-name
        (keyword (name type-kw) (str resource-name))
        (throw (ex-info (str "Duplicate resource type " type-kw " detected. "
                             "Please provide an :alias or a :name.")
                        {:type type-kw :config config})))
      :else default-key)))


;; Going to slowly work out which parts we can peel away and simplify
;; From the initial.
;; It'll likely make more sense to simply define the problem and map it out, but
;; we spent a chunk of time just writing out provider templating, so this was
;; a bit more rushed.
(defn process-stack [stack-items config initial-common-opts]
  (reduce
   (fn [acc item]
     (let [{:keys [last-resource resources-map common-opts]} acc]

       (cond
         (vector? item)
         (let [[new-res new-map new-opts]
               (handle-list-item last-resource item config common-opts)]
           {:last-resource (or new-res last-resource)
            :resources-map (merge resources-map new-map)
            :common-opts   new-opts})
         :else
         (let [[type-kw specific-config]
               (cond
                 (keyword? item) [item {}]
                 (map? item)     (first item)
                 :else (throw (ex-info "Unknown item" {:item item})))

               storage-key (derive-identity type-kw specific-config resources-map)
               _ (reset! debug-log [])
               _ (swap! debug-log conj storage-key)
               [new-res _ result-opts] 
               (handle-keyword-item last-resource
                                    type-kw
                                    config
                                    common-opts)]

           {:last-resource (or new-res last-resource)
            :resources-map (assoc resources-map storage-key new-res)
            :common-opts   (merge common-opts result-opts)}))))
   {:last-resource nil :resources-map {} :common-opts initial-common-opts}
   stack-items))


^{::clerk/visibility {:code :show :result :show}
  ::clerk/auto-expand-results? true}
(process-stack (:stack test-config-2) test-config-2 (select-keys test-config-2 [:app-name :app-namespace]))


(-> (:resources-map (process-stack (:stack test-config-2) test-config-2 (select-keys test-config-2 [:app-name :app-namespace])))
    (get :provider-template:storage-class))

^{::clerk/visibility {:code :show :result :show}
  ::clerk/auto-expand-results? true}
@debug-log