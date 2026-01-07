{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(ns notebooks.sidebar
  (:require [nextjournal.clerk :as clerk]))

{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn nav-sidebar [links]
  (clerk/html
   [:div
    [:style "
     .custom-sidebar { position: fixed; left: 0; top: 0; bottom: 0; width: 16rem; padding: 1.5rem; border-right: 1px solid #e5e7eb; background: white; z-index: 50; overflow-y: auto; }
     /* This pushes the standard Clerk content so it isn't hidden behind the sidebar */
     .viewer-notebook { margin-left: 16rem !important; max-width: calc(100% - 16rem) !important; }
     @media (max-width: 1024px) { .custom-sidebar { display: none; } .viewer-notebook { margin-left: auto !important; max-width: 100% !important; } }
     "]

    [:div.custom-sidebar.prose
     [:h3.mt-0 "Library"]
     [:ul.pl-4
      (for [[label href] links]
        [:li [:a {:href href :class "text-blue-600 hover:underline"} label]])]

     [:div.mt-8.text-sm.text-gray-500
      "Status: " [:span.text-green-600 "● Online"]]]]))

{:nextjournal.clerk/visibility {:code :hide :result :show}}
(def sidebar
  (nav-sidebar
   [["Home" "notebooks/home"]
    ["Config Processor" "notebooks/config_processor"]]))
