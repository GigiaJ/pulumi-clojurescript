(ns pulumicljs.processors.config-processor
  (:require
   [clojure.string :as str]
   [clojure.walk :as walk]
   [pulumicljs.utils.pulumi :as pulumi]
   [pulumicljs.utils.general :refer [coerce-value]]
   [pulumicljs.utils.safe-fns :refer [safe-fns]]))


(defn lift [f args]
  (if (some pulumi/output? args)
    (pulumi/apply-output (pulumi/all args)
                         #(apply f %))
    (apply f args)))

(defn chain [val f]
  (if (pulumi/output? val)
    (pulumi/apply-output val f)
    (f val)))


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