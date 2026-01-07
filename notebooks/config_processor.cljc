(ns config-processor
  (:require
   [clojure.string :as str]
   [clojure.walk :as walk]
   [safe-fns :refer [safe-fns]]
   [sidebar :as sidebar]
   [nextjournal.clerk :as clerk]
   ))

^{::clerk/visibility {:code :hide :result :hide}}
(defn is-output? [_])
^{::clerk/visibility {:code :hide :result :hide}}
(defn coerce-value [v]
  (if (string? v)
    (or (parse-long v) v)
    v))

;; Our first steps are then to start pulling apart the old parsing mechanism and build out a testing template to run against it.
;; We don't need Pulumi yet if we do it in this manner.
;; For this example we'll use Harbor since it feels diverse enough to point to.
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


(defrecord MockOutput [val])

(defn output [v] (->MockOutput v))


(defn lift [f args]
  #?(:cljs
     (if (some is-output? args)
       (.apply (pulumi/all (clj->js args))
               (fn [resolved] (apply f (js->clj resolved))))
       (apply f args))

     :clj
     (if (some #(instance? MockOutput %) args)
         (let [unwrap (fn [x] (if (instance? MockOutput x) (:val x) x))
               real-args (map unwrap args)]
           (output (apply f real-args)))
         (apply f args))))

(defn chain [val f]
  #?(:cljs (if (and (exists? val) (.-apply val))
             (.apply val f) 
             (f val))                      
     :clj
       (if (instance? MockOutput val)
         (MockOutput. (f (:val val))) 
         (f val))))                          

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
                          {:fullName (output "robot$test")
                           :secret   (output "secret-123")}
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