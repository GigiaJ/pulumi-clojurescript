(System/setProperty "CLERK_ENV" "dev")
(ns user
  (:require [nextjournal.clerk :as clerk]
            [home :as home]
            [viewers :as viewers]))



^{::clerk/visibility {:code :hide :result :hide}}
(defn start! [_]
  (clerk/serve! {:browse? false
                 :port 7777 
                 :host "0.0.0.0"
                 :config {:html-head [:script {:src "https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js"}]}
                 :watch-paths ["notebooks"]})
  (clerk/show! 'home))
