(ns utils.general (:require [clojure.walk :as walk]))


(defn new-resource [resource-type resource-name final-args provider dependencies]
  (let [base-opts (if (or (some? provider) (seq dependencies))
                    {:enableServerSideApply false}  {})
        opts (cond-> base-opts
               (some? provider)     (assoc :provider provider)
               (seq dependencies)   (assoc :dependsOn dependencies))]
    (if (seq opts)
      (new resource-type resource-name (clj->js final-args) (clj->js opts))
      (new resource-type resource-name (clj->js final-args)))))

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

(defn make-transformer
  "Given f that takes {:app-name .. :secrets ..}, where :secrets is a plain map
   (already unwrapped inside .apply), return a Helm transformer."
  [f]
  (fn [{:keys [base-values app-name secrets]}]
    (.apply secrets
            (fn [smap]
              (let [m (js->clj smap :keywordize-keys true)
                    updates (f {:app-name app-name
                                :secrets  m})
                    after (clj->js (assoc-ins base-values updates))]
                after)))))

(defn make-paths [& path-groups]
  (mapcat (fn [{:keys [paths backend]}]
            (mapv (fn [p]
                    {:path p
                     :pathType "Prefix"
                     :backend {:service backend}})
                  paths))
          path-groups))

(defn generic-make-transformer
  "Returns a Pulumi-compatible transformer that unwraps Output values via .apply."
  [f {:keys [secrets base-values]}]
  (.apply secrets
          (fn [smap]
            (let [m (js->clj smap :keywordize-keys true)
                  updates (f {:function-keys m})
                  result (clj->js (deep-merge base-values updates))]
              result))))

(defn safe-parse-int [s]
  (let [n (js/parseInt s 10)]
    (if (js/isNaN n) nil n)))

(defn string->int? [s]
  (and (string? s)
       (re-matches #"^-?\d+$" s)))

(defn- coerce-value [v]
  (if (string->int? v)
    (safe-parse-int v)
    v))

;; Whitelist functions for resolving templates. Intended to be extended.
(def ^:private safe-fns
  {'str str
   'make-paths make-paths})

(defn resolve-template [template values secondary-values]
  (walk/postwalk
   (fn [x]
     (cond
       (and (list? x) (contains? safe-fns (first x)))
       (apply (get safe-fns (first x)) (rest x))
       (symbol? x)
       (if (contains? safe-fns x)
         x
         (let [kw (keyword x)]
           (cond
             (contains? values x) (coerce-value (get values x))
             (contains? values kw) (coerce-value (get values kw))
             (contains? secondary-values x) (coerce-value (get secondary-values x))
             (contains? secondary-values kw) (coerce-value (get secondary-values kw))
             :else x)))
       :else x))
   template))

(defn generic-transform
  "Takes a creator function and executes it with resolved arguments,
   handling asynchronicity when secrets are present."
  [creator-fn opts base-values secrets options]
  (if (nil? secrets)
    (let [final-args (clj->js (deep-merge base-values (resolve-template opts {} options)))]
      (creator-fn final-args))
    (.apply secrets
            (fn [smap]
              (let [m (js->clj smap :keywordize-keys true)
                    final-args (clj->js (deep-merge base-values (resolve-template opts m options)))]
                ;;(js/console.log final-args)
                (creator-fn final-args))))))


(defn resource-factory
  [component-specs]
  (fn [resource-type provider app-name dependencies opts]
    (let [spec (get component-specs resource-type)
          resource-class (:constructor spec)]
      (if resource-class
        (new-resource resource-class app-name opts provider dependencies)
        (throw (js/Error. (str "Unknown resource type: " resource-type)))))))


(defn component-factory [create-resource]
  (fn [requested-components
       resource-type
       provider
       app-name
       dependencies
       component-opts
       defaults
       secrets
       options]

    (when (requested-components resource-type)
      (generic-transform
       (fn [final-args]
         (create-resource resource-type provider app-name dependencies final-args))
       component-opts
       defaults
       secrets
       options))))

(defn deploy-stack-factory [func]
  (fn [& args]
    (let [[component-kws [options]] (split-with keyword? args)
          requested-components (set component-kws)]
      (func requested-components options))))

(defn iterate-stack
  [provider vault-data options secrets requested-components create-component-fn component-specs lifecycle-hooks]
  (let [base-components
        (reduce
         (fn [acc [k {:keys [deps-fn opts-key defaults-fn]}]]
           (let [env {:provider provider
                      :options options
                      :secrets secrets
                      :components acc}
                 app-name (get options :resource-name)
                 deps     (deps-fn env)
                 opts     (get options opts-key)
                 defaults (defaults-fn env)
                 component (create-component-fn requested-components k provider app-name deps opts defaults secrets options)]
             (assoc acc k component)))
         {:vault-secrets vault-data}
         (select-keys component-specs requested-components))

        final-components
        (if lifecycle-hooks
          (reduce
           (fn [acc k]
             (if-let [hook (get lifecycle-hooks k)]
               (assoc acc k (hook {:options options
                                   :components acc
                                   :secrets secrets}))
               acc))
           base-components
           requested-components)
          base-components)]
    final-components))

(defn flatten-resource-groups
  "Transforms a nested resource map into a flat, qualified-keyword map.
   Example:
   (flatten-resource-groups {:k8s {:chart {} :ingress {}}})
   => {:k8s:chart {} :k8s:ingress {}}"
  [config]
  (into {}
        (mapcat
         (fn [[k v]]
           (if (and (keyword? k) (map? v))
             (map (fn [[inner-k inner-v]]
                    [(keyword (name k) (name inner-k)) inner-v])
                  v)
             [[k v]]))
         config)))


(defn- is-output? [x] (some? (and x (.-__pulumiOutput x))))

(defn p-apply-or-resolve
  "Runtime helper. If 'v' is an Output, applies 'f' to it.
   If 'v' is a plain value, calls 'f' with it."
  [v f]
  (if (is-output? v)
    (.apply v f)
    (f v)))

(defn is-output? [x]
  (some? (and x (.-__pulumiOutput x))))

(defn p-chain [v f]
  (if (is-output? v)
    (.apply v f)
    (f v)))

(defn p-map [v f]
  (p-chain v #(f %)))

(defn p-lift [v]
 (if (is-output? v) v (js/Promise.resolve v)))
