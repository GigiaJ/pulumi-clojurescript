(ns user
  (:require [nextjournal.clerk :as clerk]))

(defn start! [_]
 (clerk/serve! {:browse? false
                :watch-paths ["notebooks"]}))

