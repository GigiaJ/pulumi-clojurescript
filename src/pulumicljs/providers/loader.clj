(ns pulumicljs.providers.loader
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- camel-to-kebab [s]
  (-> s
      (str/replace #"([a-z])([A-Z])" "$1-$2")
      str/lower-case))

(defn- extract-package-name [file]
  (try
    (let [content (slurp file)
          match (re-find #"(?s)\[\s*\"(.*?)\"\s+:as" content)]
      (when match
        (second match)))
    (catch Throwable _ nil)))

(defmacro build-complete-library []
  (let [is-cljs? (boolean (:ns &env))]

    (println "--- MACRO DEBUG: Building library. Target is CLJS?" is-cljs?)

    (try
      (let [root-dir (io/file "src/pulumicljs/providers")]

        (when-not (.exists root-dir)
          (throw (ex-info (str "CRITICAL: Could not find directory at " (.getAbsolutePath root-dir)) {})))

        (let [provider-data (->> (.listFiles root-dir)
                                 (filter #(and (.isDirectory %)
                                               (.exists (io/file % "defaults.cljc"))))
                                 (map (fn [dir]
                                        (let [defaults-file (io/file dir "defaults.cljc")
                                              provider-name (.getName dir)
                                              pkg-name      (extract-package-name defaults-file)]
                                          {:dir dir
                                           :name provider-name
                                           :file defaults-file
                                           :pkg  pkg-name
                                           :ns   (symbol (str "pulumicljs.providers." provider-name ".defaults"))}))))]

          (doseq [p provider-data]
            (require (:ns p)))

          (let [get-vars (fn [p-info]
                           (for [[sym-name the-var] (ns-publics (:ns p-info))]
                             {:val @the-var
                              :sym (symbol (str (:ns p-info)) (str sym-name))
                              :provider (:name p-info)
                              :package  (:pkg p-info)}))

                all-vars (mapcat get-vars provider-data)

                valid-resources (filter (fn [item]
                                          (and (map? (:val item))
                                               (:path (:val item))))
                                        all-vars)

                flat-registry (reduce (fn [acc item]
                                        (let [path        (:path (:val item))
                                              provider    (:provider item)
                                              pkg         (:package item)

                                              resource-raw  (name (last path))
                                              resource-name (if (str/starts-with? resource-raw "-")
                                                              (subs resource-raw 1)
                                                              resource-raw)
                                              registry-key  (keyword (str provider ":" (camel-to-kebab resource-name)))
                                              ctor-form
                                              (if is-cljs?
                                                (let [base-obj (if pkg
                                                                 (list 'js/require pkg)
                                                                 (symbol provider))]
                                                  (list* '.. base-obj path))

                                                {:debug-info "Constructor not available on JVM"
                                                 :provider   (str provider)
                                                 :path       (mapv str path)})

                                              def-fn-form (list 'fn ['env]
                                                                (list (list (list :default-fn (:sym item))
                                                                            (list :options 'env))))]

                                          (assoc acc registry-key
                                                 {:constructor  ctor-form
                                                  :provider-key (keyword provider)
                                                  :defaults-fn  def-fn-form})))
                                      {}
                                      valid-resources)]

            flat-registry)))

      (catch Throwable e
        (println "MACRO CRASHED:" (.getMessage e))
        (throw e)))))