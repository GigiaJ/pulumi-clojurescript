(ns pulumicljs.processors.config-processor
  (:require
   [clojure.walk :as walk]
   [pulumicljs.utils.safe-fns :refer [safe-fns]]
   [pulumicljs.utils.general :refer [coerce-value p-> p-threading p-lift]]))

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
         (p-threading resource steps))

       ;; Standard Handlers
       (and (list? x) (contains? safe-fns (first x)))
       (let [f    (get safe-fns (first x))
             args (rest x)]
         (p-lift f (doall args)))

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