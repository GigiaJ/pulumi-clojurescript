(ns build
  (:require [nextjournal.clerk :as clerk]
            [clojure.java.io :as io]))

(defn build-site [_]
  (println "👷 checking for notebooks...")
  
  (let [files ["src/notebooks/config_processor.cljc"
               "src/notebooks/home.cljc"
               "src/notebooks/sidebar.cljc"]
        valid-files (filter #(.exists (io/file %)) files)]

    (if (seq valid-files)
      (do
        (println "Building site with:" valid-files)
        (clerk/build!
         {:paths valid-files
          :index "src/notebooks/home.cljc"
          :out-path "public"
          :compile-css false}))
      (println "ERROR: No valid notebook files found."))))