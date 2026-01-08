(ns config-processor
  (:require
   [clojure.string :as str]
   [clojure.walk :as walk]
   [clojure.test :refer [deftest is]]
   [clojure.data :refer [diff]]
   [pulumi :as pulumi]
   [general :refer [coerce-value]]
   [safe-fns :refer [safe-fns]]
   [nextjournal.clerk :as clerk]
   ))


;; Our first steps are then to start pulling apart the old parsing mechanism and build out a testing template to run against it.
;; We don't need Pulumi yet if we do it in this manner.
;; For this example we'll use Harbor since it feels diverse enough to point to.
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


;; So this is a monadic lift. A fancy way of saying this method is to operate on wrapped Pulumi output values without the function itself knowing
;; anything about Pulumi. So we can use this to handle this with Clojure or Clojurescript.
;; In short it is what unwraps and allows operating on Pulumi outputs.
^{::clerk/visibility {:code :show :result :hide}}
(defn lift [f args]
  (if (some pulumi/output? args)
    (pulumi/apply-output (pulumi/all args)
                           #(apply f %))
    (apply f args)))

;; This is a monadic bind. This allows values to be moved through. It checks the value to determine if we need to unwrap it.
;; If it is plain we execute on it immediately, and if it isn't we pass it on and defer the execution.
^{::clerk/visibility {:code :show :result :hide}}
(defn chain [val f]
  (if (pulumi/output? val)
    (pulumi/apply-output val f)
    (f val)))

;; Now we want to have a "virtual" threading operator. It is to provide a clean interpreter to the config
;; That is to say it should feel fluid to use. Idiomatic to the Clojure experience and interacting with Pulumi outputs
;; So as an example we might want the config to have: (-> :harbor:robot-account .-fullName)
;; So firstly :harbor:robot-account would represent a Pulumi Output object. So we can't treat it as a normal object.
;; Without this our configs have no way of easily stepping through the JS object itself and interact with prior step
;; results. This interaction is consistent everywhere possible, so even on exports of the data to Pulumi to store into
;; the state file we can easily interact and retrieve.
^{::clerk/visibility {:code :show :result :hide}}
(defn do-threading [val steps]
  (reduce (fn [acc step]
            (let [run-step (fn [v]
                             (cond
                               ;; Property Access (.-prop)
                               (and (symbol? step) (str/starts-with? (name step) ".-"))
                               (let [prop (subs (name step) 2)]
                                 #?(:cljs (aget v prop)
                                    :clj  (get v (keyword prop))))

                               ;; Map Lookup (get key default)
                               (and (list? step) (= (first step) 'get))
                               (get v (second step) (nth step 2 nil))

                               ;; Function Call: (my-fn arg) -> (my-fn v arg)
                               (and (list? step) (contains? safe-fns (first step)))
                               (apply (get safe-fns (first step)) v (rest step))

                               ;; Bare Function: my-fn -> (my-fn v)
                               (and (symbol? step) (contains? safe-fns step))
                               ((get safe-fns step) v)

                               :else v))]
              (chain acc run-step)))
          val steps))


;; This is effectively the config interpreter. It takes a config definition and context
;; and evaluates them. The context is typically *everything* including whatever
;; secret provider you use and a retrieval (or setter) step. This way you can
;; use evaluated secrets stored in Vault for example.
;; The postwalk works from the bottom up. So if you have {:stack [:vault:retrieve {:app-name 'app-name}]}
;; Then app-name is resolved *first* and it works its way up to vault:retrieve.
;; The conditions determine whether we need to use our virtual threading mecro, our function callers (in the safe-fns)
;; or our symbol lookup for resolving plain values at the top-level of the config.
^{::clerk/visibility {:code :show :result :hide}}
(defn resolve-template [template values]
  (walk/postwalk
   (fn [x]
     (cond
       (and (list? x) (= (first x) '->))
       (let [[_ start-val & steps] x
             resource (cond
                        ;; Magic Lookup: (:key) -> fetch from values + COERCE
                        (keyword? start-val)
                        (coerce-value (get values start-val))

                        ;; Symbol: Already resolved by postwalk, just use it
                        ;; postwalk visits children first, so the symbol branch below 
                        ;;  already ran and coerced this if it was a symbol lookup
                        :else start-val)]
         (do-threading resource steps))

       ;; Standard Handlers
       (and (list? x) (contains? safe-fns (first x)))
       (let [f    (get safe-fns (first x))
             args (rest x)]
         (lift f (doall args)))

       ;; Symbol Lookup (With Coercion)
       (symbol? x)
       (let [k (keyword x)]
         (cond
           ;; Try Keyword Match (:app-name)
           (contains? values k) (coerce-value (get values k))

           ;; Try Symbol Match ('app-name)
           (contains? values x) (coerce-value (get values x))

           ;; Fallback: It's just a symbol (maybe a function name or local var)
           :else x))

       :else x))
   template))

;; With this we can note that auto-substitution of variables with their values is occurring.
(resolve-template test-config {:app-name "harbor"})

;; However, what if we wanted to convert a value that is the result of an operation step.
^{::clerk/visibility {:code :show :result :hide}}
(defn run-simulation [stack initial-values]
  (let [final-state
        (reduce (fn [{:keys [ctx results]} item]
                  (let [resolved-item (resolve-template item ctx)

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
(run-simulation test-config {:app-name "harbor"})

;; With that we can be conclusive that at least in our simulation (using Clojure)
;; Now something interesting we could do is add a unit test against the result of the simulation.

;; So after the runtime evaluation this is what the harbor config would look like:
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
(clojure.test/run-tests)
;; Now this shows 1 passed test, so we know our config processor works exactly as we want.