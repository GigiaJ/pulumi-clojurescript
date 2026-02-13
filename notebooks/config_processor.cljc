
^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(ns config-processor
  (:require
   [clojure.test :refer [deftest is run-tests]]
   [clojure.data :refer [diff]]
   [clojure.string :as str]
   [clojure.repl :refer [source-fn]]
   [nextjournal.clerk :as clerk]
   [viewers :as v]
   [pulumicljs.processors.stack-processor :as sproc]
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
^{::clerk/visibility {:code :hide :result :show}}
;;(clerk/code (source-fn 'sproc/generic-transform))

;; generic-transform

;; We need to define how an individual resource (the individual resources that comprise this resource's steps)
;; in the Resource Config Definition
;; in order to actually provide some dynamic options later, we'll use a multi-method that handles the registered
;; components specs.
;;(clerk/code (source-fn 'pulumicljs.processors.stack-processor/deploy-resource))
;; deploy-resource #base


;; We'll define our component specs here with just a single provider for testing config execution
^{::clerk/visibility {:code :show :result :show}
  ::clerk/auto-expand-results? true}
(def component-specs provider-template-registry)

;; We should also make a simpler test-config for now until we finish our provider design
(def test-config-2 {:app-name "test-config"
                    :stack [{:alias "alias-1"
                             :provider-template:storage-class {:app-name 'app-name
                                                               :app-namespace 'app-name
                                                               :random-setting "something"
                                                               :root-level {:next-level {:lowest-value "test"}}}
                             :pulumi-options {:safe {}}}
                            {:alias "alias-2"
                             :provider-template:storage-class {:app-name 'app-name
                                                               :app-namespace 'app-name
                                                               :random-setting "something"
                                                               :deep-props {:another-prop "value"
                                                                            :deeper-props {:deepest-prop "testing"}}}
                             :pulumi-options {:safe {}}}
                            {:alias "alias-3"
                             :provider-template:storage-class {:app-name 'app-name
                                                               :app-namespace 'app-name
:prior-step-check {}
                                                               :random-setting "something"
                                                               :deep-props {:another-prop "value"
                                                                            :deeper-props {:deepest-prop "testing"}}}
                             :pulumi-options {:safe {}}}
                            ]})

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
^{::clerk/visibility {:code :show :result :show}}
;;(clerk/code (source-fn 'pulumicljs.processors.stack_processor/deploy-resource))
;; deploy-resource #default

;; Simple little helper function
;; Just using it to merge to the inner of the resource step map and associate
;; depends on to pulumi options

;; merge-into-stack-entry
;;(clerk/code (source-fn 'pulumicljs.processors.stack_processor/merge-into-stack-entry))


;; Simplify the merging...
;; However, in doing this we still fail to address the actual uniqueness where duplicates
;; can cause issue (since depends-on seems too simplistic)
;; Something to consider as well, is that the actual dependsOn should be an empty array if there is no dependency
;; provided to it. This is because pulumi will not create a dependency if it is not provided. However,
;; we don't wish to restrict the actual ability to create inter-dependency (if possible to provide it).
;; I suspect that might not be entirely feasible if the goal was to link to config entries from other stack's
;; or from other config definitions.
;; That said we want to more or less encapsulate the actual logic related to passing off to the
;; "execute" or "deploy" method here. So any combinations of data for example to build out that
;; passed off "config" is here.

;; handle-keyword-item
;;(clerk/code (source-fn 'pulumicljs.processors.stack_processor/handle-keyword-item))


;; For proper behavior we need to check in advance for duplicates as we execute and need information sequentially (no transducers for us sadly)
;; Since we do so, we'll prepare a method that checks for duplication and provides an easy and meaningful way to access the resource configuration
;; later

;; get-item-type
;;(clerk/code (source-fn 'pulumicljs.processors.stack_processor/get-item-type))


;; We need to have a consistent way to dispatch over a set of config
;; entries. This shoud realistically just be a fancy iterator
;; with the underlying logic being passed off to the handle-keyword-item
;; for actual 'processing' and the returned up to the inaptly named
;; parent method.

;; handle-list-item
;;(clerk/code (source-fn 'pulumicljs.processors.stack-processor/handle-list-item))


;; This made me start thinking about using the Clojure records
;; This will be fine for now. So we do need to count the number of duplicates
;; That exist within a config set. Once we do we then want to actually check
;; To see if they have aliases. If there are duplicates for a resource-type
;; They must be validated to have an alias or return an error back to the
;; user. In order to prevent an improper later resource reference
;; To the output of a Pulumi execution

;; derive-identity
;;(clerk/code (source-fn 'pulumicljs.processors.stack_processor/derive-identity))


;; Going to slowly work out which parts we can peel away and simplify
;; From the initial.
;; It'll likely make more sense to simply define the problem and map it out, but
;; we spent a chunk of time just writing out provider templating, so this was
;; a bit more rushed.

;; process-stack
;;(clerk/code (source-fn 'pulumicljs.processors.stack_processor/process-stack))


;; We can now see that template resolution is occurring and thus
;; symbols can be seen with their evaluated value below
^{::clerk/visibility {:code :show :result :show}
  ::clerk/auto-expand-results? true}
;;(def processed-stack (pulumicljs.processors.stack_processor/process-stack (:stack test-config-2) test-config-2 (select-keys test-config-2 [:app-name :app-namespace])))

;; The final piece we want to ensure is prior step reference expressions are evaluated
;; and able to used in the next config entry in the config definition.
;; Additionally, testing alias accessing is useful here.
;; So immediately we notice, we haven't  *given* a direct way to back reference prior steps
;; Inherently it isn't unable, but it does lack even an object passed to the individual deploy-resource
;; item that provides the context of the executed stack at the current moment.
;; More specifically, because they are "fake" objects they don't have the expected internal structure. So we may wish to adjust the map to be handled properly by the p-> macro.
^{::clerk/visibility {:code :show :result :show}
  ::clerk/auto-expand-results? true}
;;(p-> processed-stack :resources-map "alias-2" :resource-opts :deep-props :deeper-props :deepest-prop)

;; We may need to investigate further on the cross platform behavior of get
;; on string accessors to an array as prior we were using aget.
;; However, aget will not work on a Clojure map. Since our Clojure tests operate
;; against Clojure we need to make the code portable.
;; I leave this a note on this if I have to return to fix that macro behavior


;; Now the next piece is decoupling the component specs being baked into stack processor.
;; These should instead be passed (and thusly easily mutable and loadable).
;; This allows users to engage in much more dynamic and fluid consumption of providers
;; As we can actually centralize their loading and thus allow Clojure to consume their
;; Code as data.



^{::clerk/visibility {:code :hide :result :hide}
  ::clerk/auto-expand-results? true}
@debug-log

;; Tests
 
;; Test
;; dsfjdf
;; dfadsfda
