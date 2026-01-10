{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(ns sidebar
  (:require
   [clojure.string :as str]
   [clojure.java.io :as io]
   [nextjournal.clerk :as clerk]))

(defn file->nav-item [file]
  (let [path (-> (.getPath file)
                 (str/replace #"\.(cljc|clj)$" "")
                 (str/replace #"-" "_"))
        
        name (-> (.getName file)
                 (str/replace #"\.(cljc|md)$" "")
                 (str/replace #"_" " ")
                 str/capitalize)]
    {:path path :name name}))

(def notebooks
  (let [home-file {:path "/" :name "Home"}
        scanned (->> (file-seq (io/file "notebooks"))
                     (filter #(re-find #"\.(cljc|clj|md)$" (.getName %)))
                     (remove #(= "home.cljc" (.getName %)))
                     (map file->nav-item))]

    (cons home-file scanned)))

(def sidebar (clerk/html
              [:div
               [:style "
    .clerk-sidebar { 
      position: fixed; top: 0; left: 0; bottom: 0; 
      width: 260px; background: #1a202c; color: white;
      padding: 24px; font-family: sans-serif;
      z-index: 100; border-right: 1px solid #2d3748;
      overflow-y: auto; /* Allows scrolling sidebar if list is long */
    }
    
    .clerk-main { 
      margin-left: 260px; 
      padding: 40px; 
      max-width: 800px; 
    }

    .nav-link { 
      display: block; padding: 10px 14px; color: #cbd5e0; 
      text-decoration: none; border-radius: 6px; margin-bottom: 6px;
      transition: all 0.2s;
    }
    .nav-link:hover { background: #2d3748; color: #fff; }
    header { display: none !important; }

    @media (max-width: 768px) {
      .clerk-sidebar {
        position: relative; /* Unstick from side */
        width: 100%;       /* Full width */
        height: auto;      /* Height fits content */
        bottom: auto;
        border-right: none;
        border-bottom: 1px solid #2d3748;
        padding: 16px;
      }

      .clerk-sidebar nav {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
      }

      .nav-link {
        display: inline-block;
        margin-bottom: 0;
        font-size: 0.9rem;
        padding: 8px 12px;
        background: #2d3748; /* Slight background to make them look like pills */
      }

      .clerk-main {
        margin-left: 0 !important;
        padding: 20px;
        width: 100%;
      }
      
      .clerk-sidebar h2 {
        display: none;
      }
    }
  "]

               [:div.clerk-sidebar
                [:h2.text-sm.font-semibold.uppercase.tracking-wider.text-gray-500.mb-4 "Notebooks"]
                [:nav
                 (map (fn [{:keys [path name]}]
                        [:a.nav-link {:href (clerk/doc-url path)} name])
                      notebooks)]]]))

