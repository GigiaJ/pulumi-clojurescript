(ns viewers
  (:require [nextjournal.clerk :as clerk]


            [clojure.repl :refer [source-fn]]
            
            ))


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


(def multimethod-full-viewer
  {:render-fn 
   (fn [mm-var]
     (let [mm @mm-var
           defmulti-src (or (source-fn mm-var) "; Source not found")
           methods-map (methods mm)]
       (clerk/col
        (clerk/html [:h2 "Multimethod: " (str mm-var)])
        (clerk/code defmulti-src)
        (clerk/html [:h3 "Registered Methods"])
        (if (empty? methods-map)
          (clerk/html [:p [:em "No methods defined."]])
          (->> methods-map
               ;; Sort by string representation to avoid comparison errors
               (sort-by (comp str first))
               (map (fn [[dispatch-val method-fn]]
                      (clerk/col
                       (clerk/html [:h4 "Dispatch: " (pr-str dispatch-val)])
                       (if-let [src (source-fn method-fn)]
                         (clerk/code src)
                         (clerk/html [:p [:small "Source unavailable (fn: " (str method-fn) ")"]]))))))))))})
