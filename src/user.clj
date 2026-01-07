(ns user
  (:require [nextjournal.clerk :as clerk]
            [home]))

(defn start! [_]
  (clerk/serve! {:browse? false
                 :port 7777 
                 :host "0.0.0.0"
                 :watch-paths ["notebooks"]})
  (clerk/show! 'home))