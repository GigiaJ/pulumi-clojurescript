(ns utils.general
  (:require
   [clojure.walk]))

(defmacro build-registry
  "Generates a flat resource registry map from a nested provider definition map."
  [definitions]
  (let [map-entries
        (reduce-kv
         (fn [entries provider-group-kw provider-group-def]
           (let [{:keys [root-sym provider-key resources]} provider-group-def
                 provider-ns (name provider-group-kw)]
             (reduce-kv
              (fn [entries resource-kw resource-def]
                (let [{:keys [path defaults-fn defaults-name]} resource-def
                      resource-name (name resource-kw)
                      final-key (keyword provider-ns resource-name)
                      constructor-form (list* '.. root-sym path)
                      defaults-fn-form
                      (cond
                        defaults-fn defaults-fn
                        defaults-name (let [defaults-fn-sym (symbol "default" (name defaults-name))]
                                        `(fn [env] (~defaults-fn-sym (:options env))))
                        :else (let [defaults-fn-sym (symbol "default" resource-name)]
                                `(fn [env] (~defaults-fn-sym (:options env)))))
                      value-map `{:constructor ~constructor-form
                                  :provider-key ~provider-key
                                  :defaults-fn ~defaults-fn-form}]
                  (conj entries [final-key value-map])))
              entries
              resources)))
         []
         definitions)]
    `~(into {} map-entries)))


(defn- p->-replace-percent
  "Walks 'form' and replaces all instances of the symbol '%'
   with the value of 'x'."
  [form x]
  (clojure.walk/postwalk
    (fn [sub-form]
      (if (= sub-form '%) x sub-form))
    form))

(defmacro pulet
  "Sequential binding for Pulumi Outputs.
   Looks like let*, but each binding is chained."
  [bindings & body]
  (if (empty? bindings)
    `(do ~@body)
    (let [[sym val & rest] bindings]
      `(p-chain ~val
                (fn [~sym]
                  (pulet [~@rest] ~@body))))))




(defmacro p-> [x & forms]
  (let [wrap (fn [acc form]
               (cond
                 (map? form)
                 `(p-chain ~acc (fn [val#] ~form))

                 (keyword? form)
                 `(p-chain ~acc #(get % ~form))

                 (string? form)
                 `(p-chain ~acc #(aget % ~form))

                 (symbol? form)
                 `(p-chain ~acc #(~form %))

                 (list? form)
                 `(p-chain ~acc ~form)
                 

                 :else
                 (throw (ex-info "Unsupported form in p->" {:form form}))))]
    (reduce wrap x forms)))

