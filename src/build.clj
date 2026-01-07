(System/setProperty "CLERK_ENV" "production")
(ns build
  (:require [nextjournal.clerk :as clerk]))



(defn build-site [_]
  (clerk/build!
   {:paths ["notebooks/*"]
    :out-path "public"
    :index "notebooks/home.cljc"
    :compile-css false}))