(ns pulumicljs.utils.general
  (:require
   [clojure.string :as str]
   [pulumicljs.utils.pulumi :refer [output? apply-output all]]
   [pulumicljs.utils.safe-fns :refer [safe-fns]]))

;; Maybe tweak the name
(defmacro defregistry [registry-sym definitions]
  (let [map-entries
        (reduce-kv
         (fn [entries provider-group-kw provider-group-def]
           (let [{:keys [provider-key resources]} provider-group-def
                 provider-ns (name provider-group-kw)]
             (reduce-kv
              (fn [entries resource-kw resource-def]
                (let [{:keys [path defaults-fn defaults-name]} resource-def
                      resource-name (name resource-kw)
                      final-key (keyword (str provider-ns ":" resource-name))
                      constructor-form (list* (let [fake-root (symbol (name provider-group-kw))]
                                                (list 'quote (list* '.. fake-root path))))

                      defaults-fn-form
                      (cond
                        defaults-fn defaults-fn
                        defaults-name (let [sym-name (str (name defaults-name))]
                                        `(fn [env#] (~(symbol sym-name) (:options env#))))
                        :else (let [sym-name (str resource-name)]
                                `(fn [env#] (~(symbol sym-name) (:options env#)))))

                      value-map `{:constructor ~constructor-form
                                  :provider-key ~provider-key
                                  :defaults-fn ~defaults-fn-form}]
                  (conj entries [final-key value-map])))
              entries
              resources)))
         []
         definitions)]

    `(def ~registry-sym ~(into {} map-entries))))

(defn coerce-value [v]
  (if (string? v)
    (or (parse-long v) v)
    v))

(defn assoc-ins [m path-vals]
  (reduce (fn [acc [path val]] (assoc-in acc path val)) m path-vals))

(declare deep-merge)

(defn merge-by-name
  "Merges two vectors of maps by :name key."
  [a b]
  (let [a-map (into {} (map #(vector (:name %) %) a))
        b-map (into {} (map #(vector (:name %) %) b))
        merged (merge-with deep-merge a-map b-map)]
    (vec (vals merged))))

(defn deep-merge
  "Recursively merges maps and intelligently merges vectors of maps by :name."
  [a b]
  (cond
    (nil? b) a
    (and (map? a) (map? b))
    (merge-with deep-merge a b)

    (and (vector? a) (vector? b)
         (every? map? a) (every? map? b)
         (some #(contains? % :name) (concat a b)))
    (merge-by-name a b)
    :else b))


(defn p-lift [f args]
  (if (some output? args)
    (apply-output (all args)
                  (fn [resolved-args] (apply f resolved-args)))
    (apply f args)))

(defn p-chain [val f]
  (if (output? val)
    (apply-output val f)
    (f val)))

(defn p-threading [val steps]
  (reduce (fn [acc step]
            (let [run-step
                  (fn [v]
                    (cond
                      ;; Keyword: (:my-key) -> (get v :my-key)
                      (keyword? step)
                      (get v step)

                      ;; String: ("prop") -> (aget v "prop")
                      (string? step)
                      #?(:cljs (aget v step)
                         :clj  (get v step)) ;; Fallback for JVM

                      ;; Property Access Symbol: (.-prop)
                      (and (symbol? step) (str/starts-with? (name step) ".-"))
                      (let [prop (subs (name step) 2)]
                        #?(:cljs (aget v prop)
                           :clj  (get v (keyword prop))))

                      ;; Map Lookup via List: (get :key default)
                      (and (list? step) (= (first step) 'get))
                      (apply get v (rest step)) ;; Cleaner apply

                      ;; Function Call (List): (my-fn arg)
                      (and (list? step) (contains? safe-fns (first step)))
                      (apply (get safe-fns (first step)) v (rest step))

                      ;; Bare Function (Symbol): my-fn
                      (and (symbol? step) (contains? safe-fns step))
                      ((get safe-fns step) v)

                      :else
                      (throw (ex-info "Unknown step in p-threading" {:step step}))))]
              (p-chain acc run-step)))
          val steps))

(defmacro p-> [x & forms]
  (let [wrap (fn [acc form]
               (cond
                 ;; Keyword: (:key)
                 (keyword? form)
                 `(p-chain ~acc (fn [v#] (get v# ~form)))

                 ;; String: ("prop")
                 (string? form)
                 `(p-chain ~acc (fn [v#] (aget v# ~form)))

                 ;; Property Access: (.-prop)
                 (and (symbol? form) (str/starts-with? (name form) ".-"))
                 (let [prop (subs (name form) 2)]
                   `(p-chain ~acc (fn [v#] (aget v# ~prop))))

                 ;; Anonymous Function: (fn [x] ...) or (fn* [] ...)
                 (and (list? form)
                      (symbol? (first form))
                      (#{"fn" "fn*"} (name (first form))))
                 `(p-chain ~acc (fn [v#] (~form v#)))

                 ;; Bare Symbol (Function): my-func
                 (symbol? form)
                 `(p-chain ~acc (fn [v#] (~form v#)))

                 ;; Standard Function Call: (my-func arg)
                 (list? form)
                 (let [f (first form)
                       args (rest form)]
                   `(p-chain ~acc (fn [v#] (~f v# ~@args))))

                 :else
                 (throw (ex-info "Unsupported form in p->" {:form form}))))]
    (reduce wrap x forms)))