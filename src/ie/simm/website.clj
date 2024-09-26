(ns ie.simm.website
  (:require  [nextjournal.markdown :as md]
             [nextjournal.markdown.transform :as md.transform]
             [nextjournal.markdown.parser :as md.parser]
             [ie.simm.config :refer [config]]))

(def base-url (:base-url config))

(def internal-link-tokenizer
  (md.parser/normalize-tokenizer
   {:regex #"\[\[([^\]]+)\](\[([^\]]+)\])?\]"
    :handler (fn [match] {:type :internal-link
                          :text (match 1)})}))


(def md-renderer
  (assoc md.transform/default-hiccup-renderers
        ;; :doc specify a custom container for the whole doc
         :doc (partial md.transform/into-markup [:div.viewer-markdown])

        ;; :text is funkier when it's zinc toned 
        ;; :text (fn [_ctx node] [:span #_{:style {:color "#71717a"}} (:text node)])
        ;; :plain fragments might be nice, but paragraphs help when no reagent is at hand
         :plain (partial md.transform/into-markup [:p #_{:style {:margin-top "-1.2rem"}}])

         :formula (fn [ctx {:keys [text content]}] (str "$$" text "$$"))
         #_(partial md.transform/into-markup [:p #_{:style {:margin-top "-1.2rem"}}])

         :block-formula (fn [ctx {:keys [text content]}] (str "$$" text "$$"))
         ;:link (fn [ctx {:as node :keys [attrs]}] (md.transform/into-markup [:a {:href (:href attrs)}] ctx node))
        ;; :ruler gets to be funky, too
        ; :ruler (constantly [:hr {:style {:border "2px dashed #71717a"}} ])
         ))

(defn md-render [s]
  (md.transform/->hiccup
   md-renderer
   (md/parse (update md.parser/empty-doc :text-tokenizers concat [internal-link-tokenizer md.parser/hashtag-tokenizer])
             s)))

(defn default-chrome [title & body]
  [:html
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:link {:rel "stylesheet" :href "https://cdn.jsdelivr.net/npm/katex@0.16.10/dist/katex.min.css" :integrity "sha384-wcIxkf4k558AjM3Yz3BBFQUbk/zgIYC2R0QpeeYb+TwlBVMrlgLqwRjRtGZiK7ww" :crossorigin "anonymous"}]
    [:script {:defer true :src "https://cdn.jsdelivr.net/npm/katex@0.16.10/dist/katex.min.js" :integrity "sha384-hIoBPJpTUs74ddyc4bFZSM1TVlQDA60VBbJS0oA934VSz82sBx1X7kSx2ATBDIyd" :crossorigin "anonymous"}]
    [:script {:defer true :src "https://cdn.jsdelivr.net/npm/katex@0.16.10/dist/contrib/auto-render.min.js" :integrity "sha384-43gviWU0YVjaDtb/GhzOouOXtZMP/7XUzwPTstBeZFe/+rCMvRwr4yROQP43s0Xk" :crossorigin "anonymous"
              :onload "renderMathInElement(document.body);"}]
    [:link {:rel "stylesheet" :href "https://unpkg.com/boxicons@2.1.4/css/boxicons.min.css"}]
    [:link {:rel "stylesheet" :href "https://cdn.jsdelivr.net/npm/bulma@1.0.0/css/bulma.min.css"}]
    [:script {:src "https://unpkg.com/htmx.org@1.9.11" :defer true}]
    [:script {:src "https://unpkg.com/hyperscript.org@0.9.12" :defer true}]
   
    [:title title]]
   [:body
    [:section {:class "hero is-fullheight"}
     [:div {:class "hero-head"}
      [:header {:class "navbar"}
       [:div {:class "container"}
        [:div {:class "navbar-brand"}
         [:a {:class "navbar-item" :href "/"}
          [:img {:src "/simmie.png" :alt "Simmie logo"}]]
         [:span {:class "navbar-burger" :data-target "navbarMenu"}
          [:span]
          [:span]
          [:span]]]
        [:div {:id "navbarMenu" :class "navbar-menu"}
         [:div {:class "navbar-start"}
          [:a {:class "navbar-item" :href "#"} "Home"]
          [:a {:class "navbar-item" :href "#"} "Features"]
          [:a {:class "navbar-item" :href "#"} "About"]]]]]]
     (vec (concat [:div {:class "hero-body"}] body))
     [:div {:class "hero-foot"}
      [:footer {:class "footer"}
       [:div {:class "content has-text-centered"}
        [:p "Copyright © 2024 Christian Weilbach. All rights reserved."]]]]]]])
