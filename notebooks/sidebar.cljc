{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(ns sidebar
  (:require
   [clojure.string :as str]
   [nextjournal.clerk :as clerk]))

;; Clerk has a bug where the set index.html makes it virtually impossible to create a crisp subpath experience without fixing the path
(defn fix-path [href]
  (if (or (= href "home") (= href "home.cljc"))
    "../../"
    (let [clean-slug (-> href
                         (clojure.string/replace #"^/+" "")
                         (clojure.string/replace #"^notebooks/" ""))]
      (str "/notebooks/" clean-slug))))


(defn corrected-links [links]
  (for [[label href] links]
    [:li
     [:a {:href (fix-path href)
          :class "block text-blue-600 hover:underline"}
      label]]))

(defn nav-sidebar [links]
  (clerk/html
   [:div
    [:style "
     .custom-sidebar { position: fixed; left: 0; top: 0; bottom: 0; width: 16rem; padding: 1.5rem; border-right: 1px solid #e5e7eb; background: white; z-index: 50; overflow-y: auto; }
     .viewer-notebook { margin-left: 16rem !important; max-width: calc(100% - 16rem) !important; }
     @media (max-width: 1024px) { .custom-sidebar { display: none; } .viewer-notebook { margin-left: auto !important; max-width: 100% !important; } }
     "]

    [:div.custom-sidebar.prose
     [:h3.mt-0 "Library"]
     [:ul.pl-4
      (corrected-links links)]]]))

{:nextjournal.clerk/visibility {:code :hide :result :show}}
(def sidebar
  (nav-sidebar
   [["Home" "home"]
    ["Config Processor" "config_processor"]]))