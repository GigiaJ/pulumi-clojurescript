(ns viewers
  (:require [nextjournal.clerk :as clerk]))


(def mermaid-viewer
  {:transform-fn clerk/mark-presented
   :render-fn '(fn [value]
                 (when value
                   [nextjournal.clerk.render/with-d3-require {:package ["mermaid@8.14/dist/mermaid.js"]}
                    (fn [mermaid]
                      [:div {:ref (fn [el]
                                    (when el
                                      (.initialize mermaid (clj->js {:startOnLoad false}))
                                      (.render mermaid (str "id" (js/Math.floor (js/Math.random 1000)))
                                               value
                                               #(set! (.-innerHTML el) %))))}])]))})