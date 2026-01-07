(ns build
  (:require [nextjournal.clerk :as clerk]
            [clojure.java.io :as io]))

(defn build-site [_]
  (println "👷 checking for notebooks...")
  
  (let [files ["notebooks/config_processor.cljc"
               "notebooks/home.cljc"
               "notebooks/sidebar.cljc"]
        valid-files (filter #(.exists (io/file %)) files)]

    (if (seq valid-files)
      (do
        (println "Building site with:" valid-files)
        (clerk/build!
         {:paths ["notebooks/*"]
          :index "notebooks/home.cljc"
          :out-path "public"
          :compile-css false}))
      (println "ERROR: No valid notebook files found."))))