(ns pulumicljs.utils.general)

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