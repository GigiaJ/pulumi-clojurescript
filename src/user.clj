(System/setProperty "CLERK_ENV" "dev")
(ns user
  (:require [nextjournal.clerk :as clerk]
            [home :as home]
            [viewers :as viewers]))


(when-not (resolve 'clojure.core/clj->js)
  (intern 'clojure.core 'clj->js
          (fn [x]
            (clojure.walk/stringify-keys x))))

(when-not (resolve 'clojure.core/js->clj)
  (intern 'clojure.core 'js->clj
          (fn [x & [opts]]
            (let [{:keys [keywordize-keys]} (if (map? opts) opts (apply hash-map opts))]
              (if keywordize-keys
                (clojure.walk/keywordize-keys x)
                x)))))

^{::clerk/visibility {:code :hide :result :hide}}
(defn start! [_]
  (clerk/serve! {:browse? false
                 :port 7777 
                 :host "0.0.0.0"
                 :config {:html-head [:script {:src "https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js"}]}
                 :watch-paths ["notebooks"]})
  (clerk/show! 'home))
