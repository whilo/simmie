(ns ie.simm.runtimes.assistance
  "Active conversation mode that tries to extract factual and procedural knowledge from the user for later use in reasoning and recall.
   
   Properties: stateful, persistent, durable"
  (:require [ie.simm.languages.bindings :as lb]
            [ie.simm.languages.gen-ai :refer [cheap-llm reasoner-llm stt-basic image-gen]]
            [ie.simm.languages.web-search :refer [search]]
            [ie.simm.languages.browser :refer [extract-body]]
            [ie.simm.languages.chat :refer [send-text! send-photo! send-document!]]
            [ie.simm.prompts :as pr]
            [ie.simm.db :refer [ensure-conn conversation extract-links msg->txs window-size]]
            [superv.async :refer [<?? go-try S go-loop-try <? >? put? go-for] :as sasync]
            [clojure.core.async :refer [chan pub sub mult tap timeout] :as async]
            [taoensso.timbre :refer [debug info warn error]]
            [datahike.api :as d]
            [hasch.core :refer [uuid]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [hiccup2.core :as h]
            [hiccup.page :as hp]
            [hickory.core :as hk]
            [nextjournal.markdown :as md]
            [nextjournal.markdown.transform :as md.transform]
            [nextjournal.markdown.parser :as md.parser]
            [clojure.test :as ct])
  (:import [java.util.zip ZipEntry ZipOutputStream]))

(comment
 ;; idealized version of the following summarize function

;; implicit let
  (let [conv (conversation window-size)
        summarization (reasoner-llm (fill-in prompts/summarization conv))
        _ (add-summarization! summarization)
        notes (extract-notes summarization)
        _ (for [note notes]
            (update-note! (reasoner-llm (fill-in prompts/note note summarization conv))))])
  )

(defn summarize [S conn conv chat]
  (go-try S
          (debug "=========================== SUMMARIZING ===============================")
          (let [db @conn
                summarization  (<? S (reasoner-llm (format pr/summarization conv)))
                messages (->> (d/q '[:find ?d ?e
                                     :in $ ?chat
                                     :where
                                     [?c :chat/id ?chat]
                                     [?e :message/chat ?c]
                                     [?e :message/date ?d]]
                                   db (:id chat))
                              (sort-by first)
                              (take-last window-size)
                              (map second))
                note-titles (extract-links summarization)
                _ (debug "=========================== CREATING NOTES ===============================")
                new-notes
                (<? S (async/into []
                                  (go-for S [[i note] (partition 2 (interleave (iterate inc 2) note-titles))
                                             :let [[nid body] (first (d/q '[:find ?n ?b :in $ ?t :where [?n :note/title ?t] [(get-else $ ?n :note/body "EMPTY") ?b]] db note))
                                                   prompt (format pr/note note body #_summarization conv)
                                                   new-body (<? S (reasoner-llm prompt))]
                                             :when (not (.contains new-body "SKIP"))
                                             :let [new-refs (extract-links new-body)
                                                   ref-ids (mapv first (d/q '[:find ?n
                                                                              :in $ [?t ...]
                                                                              :where
                                                                              [?n :note/title ?t]]
                                                                            db new-refs))]]
                                          {:db/id (or nid (- i))
                                           :note/title note
                                           :note/body new-body
                                           :note/link ref-ids
                                           :note/summary -1})))]
            (debug "=========================== STORING NOTES ===============================")
            (debug "Summarization:" summarization)
            (debug "summarization links" (extract-links summarization))
            (d/transact conn (concat
                              [{:db/id -1
                                :conversation/summary summarization
                                :conversation/date (java.util.Date.)
                                :conversation/message messages}]
                              new-notes))
                              ;; keep exports up to date
            (doseq [[t b] (map (fn [{:keys [note/title note/body]}] [title body]) new-notes)]
              (debug "writing note" t)
              ;; write to org file in chats/chat-id/title.org
              (let [f (io/file (str "chats/" (:id chat) "/" t ".md"))]
                (io/make-parents f)
                (with-open [w (io/writer f)]
                  (binding [*out* w]
                    (println b)))))
            (extract-links summarization))))

(defn zip-notes [chat-id]
  (let [zip-file (io/file (str "chats/" chat-id ".zip"))
        zip-out (io/output-stream zip-file)
        zip (ZipOutputStream. zip-out)
        notes-dir (io/file (str "chats/" chat-id))
        _ (io/make-parents notes-dir)
        _ (io/make-parents zip-file)
        _ (doseq [f (rest (file-seq notes-dir))]
            (let [entry (ZipEntry. (str chat-id "/" (.getName f)))]
              (.putNextEntry zip entry)
              (with-open [in (io/input-stream f)]
                (io/copy in zip))
              (.closeEntry zip)))]
    (.close zip)
    (.close zip-out)
    zip-file))

(def base-url "https://ec2-52-32-225-23.us-west-2.compute.amazonaws.com")

(def internal-link-tokenizer
  (md.parser/normalize-tokenizer
   {:regex #"\[\[([^\]]+)\](\[([^\]]+)\])?\]"
    :handler (fn [match] {:type :internal-link
                          :text (match 1)})}))

(comment
  ;; figure out separate extraction of link https://nextjournal.github.io/markdown/notebooks/parsing_extensibility/
  (md.parser/tokenize-text-node internal-link-tokenizer {} {:text "some [[set]] of [[wiki][wiki]] link"})
  )

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

(defn response [body & [status]]
  {:status (or status 200)
   :body (str (hp/html5 body))}) 

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


(defn render-supertag [tag schema]
  [:div.content
   tag
   [:ul
    (->> schema
         (filter (fn [[k _v]] (let [ns (str (namespace k))] (= ns "issue"))))
         (map (fn [[k v]]
                [:li [:span.container
                [:span.content
                      (name k)
                      (case (:db/valueType v)
                        :db.type/string [:input.input {:type "text" :name (name k)}]
                        :db.type/long [:input.input {:type "text" :name (name k)}]
                        :db.type/instant [:input.input {:type "text" :name (name k)}]
                        "unknown")]]])))]])

(def test-schema {:issue/scheduled #:db{:ident :issue/scheduled, :valueType :db.type/instant, :cardinality :db.cardinality/one, :id 23}, :note/link #:db{:ident :note/link, :valueType :db.type/ref, :cardinality :db.cardinality/many, :id 29}, :chat/all_members_are_administrators #:db{:ident :chat/all_members_are_administrators, :valueType :db.type/boolean, :cardinality :db.cardinality/one, :id 11}, :from/username #:db{:id 7, :ident :from/username, :valueType :db.type/string, :cardinality :db.cardinality/one}, :conversation/date #:db{:ident :conversation/date, :valueType :db.type/instant, :cardinality :db.cardinality/one, :id 82}, :message/text #:db{:ident :message/text, :valueType :db.type/string, :cardinality :db.cardinality/one, :id 18}, :chat/title #:db{:ident :chat/title, :valueType :db.type/string, :cardinality :db.cardinality/one, :id 13}, :issue/title #:db{:id 20, :ident :issue/title, :valueType :db.type/string, :cardinality :db.cardinality/one}, :note/body #:db{:ident :note/body, :valueType :db.type/string, :cardinality :db.cardinality/one, :id 28}, :from/first_name #:db{:ident :from/first_name, :valueType :db.type/string, :cardinality :db.cardinality/one, :id 5}, :from/is_bot #:db{:id 4, :ident :from/is_bot, :valueType :db.type/boolean, :cardinality :db.cardinality/one}, :from/id #:db{:ident :from/id, :valueType :db.type/long, :cardinality :db.cardinality/one, :unique :db.unique/identity, :id 3}, :message/from #:db{:ident :message/from, :valueType :db.type/ref, :cardinality :db.cardinality/one, :id 2}, :issue/priority #:db{:ident :issue/priority, :valueType :db.type/long, :cardinality :db.cardinality/one, :id 22}, :message/link #:db{:ident :message/link, :valueType :db.type/string, :cardinality :db.cardinality/many, :id 19}, :message/id #:db{:id 1, :ident :message/id, :valueType :db.type/long, :cardinality :db.cardinality/one, :unique :db.unique/identity}, :conversation/link #:db{:ident :conversation/link, :valueType :db.type/string, :cardinality :db.cardinality/many, :id 25}, :conversation/message #:db{:ident :conversation/message, :valueType :db.type/ref, :cardinality :db.cardinality/many, :id 26}, :chat/username #:db{:id 15, :ident :chat/username, :valueType :db.type/string, :cardinality :db.cardinality/one}, :conversation/summary #:db{:id 24, :ident :conversation/summary, :valueType :db.type/string, :cardinality :db.cardinality/one}, :chat/type #:db{:ident :chat/type, :valueType :db.type/string, :cardinality :db.cardinality/one, :id 16}, :message/chat #:db{:ident :message/chat, :valueType :db.type/ref, :cardinality :db.cardinality/one, :id 9}, :from/last_name #:db{:id 6, :ident :from/last_name, :valueType :db.type/string, :cardinality :db.cardinality/one}, :note/title #:db{:id 27, :ident :note/title, :valueType :db.type/string, :unique :db.unique/identity, :cardinality :db.cardinality/one}, :message/date #:db{:id 17, :ident :message/date, :valueType :db.type/instant, :cardinality :db.cardinality/one}, :issue/id #:db{:id 21, :ident :issue/id, :valueType :db.type/long, :unique :db.unique/identity, :cardinality :db.cardinality/one}, :chat/first_name #:db{:id 12, :ident :chat/first_name, :valueType :db.type/string, :cardinality :db.cardinality/one}, :chat/id #:db{:id 10, :ident :chat/id, :valueType :db.type/long, :cardinality :db.cardinality/one, :unique :db.unique/identity}, :message/url #:db{:id 75, :ident :message/url, :valueType :db.type/string, :cardinality :db.cardinality/many}, :from/language_code #:db{:ident :from/language_code, :valueType :db.type/string, :cardinality :db.cardinality/one, :id 8}, :chat/under-assistance? #:db{:id 14, :ident :chat/under-assistance?, :valueType :db.type/boolean, :cardinality :db.cardinality/one}, :note/summary #:db{:id 30, :ident :note/summary, :valueType :db.type/ref, :cardinality :db.cardinality/many}})

(defn chat-overview [peer {{:keys [chat-id]} :path-params}]
  (let [conn (ensure-conn peer chat-id)
        title (or (:chat/title (d/entity @conn [:chat/id (Long/parseLong chat-id)])) "Noname chat")
        notes (->> (d/q '[:find ?t :where [?n :note/title ?t]] @conn)
                   (map first)
                   sort)]
    (response 
     (default-chrome title
      [:div {:class "container"}
       [:nav {:class "breadcrumb" :aria-label "breadcrumbs"}
        [:ul {}
         [:li [:span #_{:href "/#"} [:span {:class "icon is-small"} [:i {:class "bx bx-circle"}]] [:span "Systems"]]]
         [:li.is-active
          [:a {:href (str "/chats/" chat-id)}
           [:span {:class "icon is-small"} [:i {:class "bx bx-chat"}]]
           [:span title]]]]]
       [:div.content "The following points describes how to break down what you want into actionable and quantifiable steps that can potentially be automated by agents."]
       [:div {:class "container"}
        [:h2 {:class "title is-2 is-spaced" :id "goals"}
         [:a {:class "" :href "goals"} [:i {:class "bx bx-target-lock"}]]
         [:span {:class ""} "Goals"]]
        [:div.content
         [:p "Goals describe what you generally try to achieve in this process."]]
        [:div {:class "content"}
         [:ul
          [:li "Provide a simple and efficient way to keep track of important information and conversations."]
          [:li "Designed to be easy to use and to help you stay organized."]
          [:li "Able to learn from your interactions and improve over time."]]]]
       [:div {:class "container"}
        [:h2 {:class "title is-2 is-spaced" :id "issues"}
         [:a {:class "" :href "issues"} [:i {:class "bx bx-check-square"}]]
         [:span {:class ""} "Issues"]]
        [:div {:class "content"}
         [:p "Here you list all issues, problems, general situations or detailed scenarios where one or more of the goals are not met. If possible describe the value that solving each issue would provide to the system."]]
        [:div {:class "content"}
         [:ul
          [:li "Only Telegram is supported, considered Slack, Discord, Whatsapp(?). Value: Slack opens business clients. Value: More potential users."]
          [:li "Automatically infer and update this process description from context with conversational AI. Value: More users if system makes sense and is accepted."]
          [:li "Intelligently select context from memory to learn over time. Value: More accurate, relevant and personalized responses."]]]]
       [:div {:class "container"}
        [:h2 {:class "title is-2 is-spaced" :id "startegies"}
         [:a {:class "" :href "strategies"} [:i {:class "bx bx-map-alt"}]]
         [:span {:class ""} "Strategy"]]
        [:div {:class "content"}
         [:p "Here you list strategies that can be used to achieve the goals. They are general approaches that can be taken to solve the issues and achieve the goals. They help to derive actions that can be taken to solve the issues and achieve the goals."]]
        [:div {:class "content"}
         [:ul
          [:li "Implement general htmx UI components for Datahike schema."]]]]
       [:div {:class "container"}
        [:h2 {:class "title is-2 is-spaced" :id "startegies"}
         [:a {:class "" :href "strategies"} [:i {:class "bx bx-joystick-button"}]]
         [:span {:class ""} "Actions"]]
        [:div {:class "content"}
         [:p "Here you list actions that can be taken to achieve the goals. They are specific steps that can be taken to solve the issues and achieve the goals."]]
        [:div {:class "content"}
         [:ul
          [:li "Use a simple note-taking system to keep track of important information and conversations."]]]]
       [:div {:class "container"}
        [:h2 {:class "title is-2 is-spaced" :id "progress-metrics"}
         [:a {:class "" :href "progress-metrics"} [:i {:class "bx bx-up-arrow-circle"}]]
         [:span {:class ""} "Progress metrics"]]
        [:div {:class "content"}
         [:p "Define progress metrics that measure how well each issue is being adressed. Typically they are quantifiable and can be measured over time, e.g. daily, weekly and monthly. Where possible they should quantify how much value is provided for solving each issue. For example if a goal is to increase the number of users, a progress metric could be the number of new users per day. For the monthly revenue the number of daily users could be a progress metric, but a translation factor needs to be defined."]]
        [:div {:class "content"}
         [:ul
          [:li "New users/day."]
          [:li "Company revenue/month."]]]]
       [:div {:class "container"}
        [:h2 {:class "title is-2 is-spaced" :id "resources"}
         [:a {:class "" :href "resources"} [:i {:class "bx bx-dollar-circle"}]]
         [:span {:class ""} "Resources"]]
        [:div {:class "content"}
         [:p "These are resources you have availble to tackle the issues and achieve the goals. They can be people, tools, data, or anything else that can help you."]]
        [:div {:class "content"}
         [:ul
          [:li "Telegram chat."]
          [:li "Datahike database."]
          [:li "1000 USD"]
          [:li "100 hours"]]]]
       [:div {:class "container"}
        [:h2 {:class "title is-2 is-spaced" :id "bots"}
         [:a {:class "" :href "bots"} [:i {:class "bx bx-bot"}]]
         [:span {:class ""} "Agents and Processes"]]
        [:div {:class "content"}
         [:p "Support agents are running in the background to assist you. They are able to answer questions, collaborate, provide summaries, and help you with your tasks. They are also able to learn from your interactions and improve over time. There are also simpler processes that can be triggered by certain keywords or commands. They are also called bots in some contexts."]]
        [:div {:class "container"}
         [:div.content
          [:h3 {:class "title is-3 is-spaced" :id "note-taker"}
           [:a {:class "" :href "note-taker"} [:i {:class "bx bx-bot"}]]
           [:span {:class ""} "Note-taker"]]
          [:p "The note-taker is a simple note-taking system that allows you to keep track of important information and conversations. You can create new notes, edit existing ones, and link them together. The note-taker will also automatically summarize conversations and create new notes for you."]
          [:div.container
           [:div.content
            [:h4 {:class "title is-4 is-spaced" :id "notes"}
             [:a {:class "" :href "notes"} "# "]
             [:span {:class ""} "Notes"]]
            [:p "These are all notes that have been created in this chat. You can click on a note to view its content, edit it, or delete it. You can also download all notes as a zip file."]
            [:a {:class "button" :href (str "/download/chat/" chat-id "/notes.zip")} [:span {:class "icon is-small"} [:i {:class "bx bx-download"}]] [:span "Download"]]
            [:div {:class "content"}
             (if (seq notes)
               [:ul (map (fn [f] [:li [:a {:href (str "/chats/" chat-id "/notes/" f)} f]]) notes)]
               "No notes.")]]]]]
        [:div.container
         [:div.content
          [:h3 {:class "title is-3 is-spaced" :id "issue-tracker"}
           [:a {:class "" :href "issue-tracker"} [:i {:class "bx bx-bot"}]]
           [:span {:class ""} "Issue tracker"]]
          [:div.content
           [:p "The issue tracker is a simple system that allows you to keep track of tasks, bugs, and other issues. you can create new issues, assign them to people, set priorities, and schedule them. the issue tracker will also automatically remind you of upcoming deadlines in the chat and help you to stay organized."]]
          [:div.container
           [:div.content
            [:h4 {:class "title is-4 is-spaced" :id "issues"}
             [:a {:class "" :href "issues"} "# "]
             [:span {:class ""} "Issues"]]
            (let [issues (->> (d/q '[:find ?t :where [?i :issue/title ?t]] @conn)
                              (map first)
                              sort)]
              (if (seq issues)
                [:ul
                 (map (fn [f] [:li [:a {:href (str "/chats/" chat-id "/issues/" f)} f]]) issues)]
                "No issues."))]]]]]]))))

(defn view-note [peer {{:keys [chat-id note]} :path-params}]
  (let [conn (ensure-conn peer chat-id)
        body (:note/body (d/entity @conn [:note/title note]))
        chat-title (:chat/title (d/entity @conn [:chat/id (Long/parseLong chat-id)]))
        summaries (->>
                   (d/q '[:find ?s ?d ?t ?n ?f ?l
                          :in $ ?note
                          :where
                          [?n :note/title ?note]
                          [?n :note/summary ?c]
                          [?c :conversation/summary ?s]
                          [?c :conversation/message ?m]
                          [?m :message/date ?d]
                          [?m :message/text ?t]
                          [?m :message/from ?u]
                          [(get-else $ ?u :from/username "") ?n]
                          [(get-else $ ?u :from/first_name "") ?f]
                          [(get-else $ ?u :from/last_name "") ?l]]
                        @conn note)
                   (reduce (fn [m [s d t n f l]]
                             (update m s (fnil conj []) [d t n f l]))
                           {}))
        linking-notes (d/q '[:find ?lt
                             :in $ ?t
                             :where
                             [?n :note/title ?t]
                             [?l :note/link ?n]
                             [?l :note/title ?lt]]
                           @conn note)]
    (response
     (default-chrome note
      [:div {:class "container"}
       [:nav {:class "breadcrumb" :aria-label "breadcrumbs"}
        [:ul {} #_[:li [:p "Process"]]
         [:li [:span #_{:href "/#"} [:span {:class "icon is-small"} [:i {:class "bx bx-circle"}]] [:span "Systems"]]]
         [:li [:a {:href (str "/chats/" chat-id)} [:span {:class "icon is-small"} [:i {:class "bx bx-chat"}]] [:span (or chat-title "Noname chat")]]]
         [:li.is-active [:a {:href (str "/chats/" chat-id "/notes/" note)} [:span {:class "icon is-small"} [:i {:class "bx bx-note"}]] [:span note]]]]]
       [:article {:id "note" :class "message"}
        [:div {:class "message-header"}
         [:p note]
         [:button {:class "delete" :hx-post (str "/chats/" chat-id "/notes/" note "/delete") :hx-trigger "click" :hx-target "#note" :hx-confirm "Are you sure you want to delete this note?" :hx-swap "outerHTML"}]]
        [:div {:class "message-body content"}
         (if body (md-render body) "Note does not exist yet.")
         [:button {:class "button is-primary" :hx-post (str "/chats/" chat-id "/notes/" note "/edit") :hx-target "#note" :hx-trigger "click" :hx-swap "outerHTML"} "Edit"]]]
       (when (seq linking-notes)
         [:div {:class "content"}
          [:h4 {:class "title is-4 is-spaced" :id "incoming-pointers"}
           [:a {:class "" :href "incoming-pointers"} "# "]
           [:span {:class ""} "Pointing to this note"]]
          (for [[ln] linking-notes]
            [:div {:class "content"}
             [:a {:href (str "/chats/" chat-id "/notes/" ln)} ln]])])
       (when (seq summaries)
         [:div {:class "content"}
          (for [[i [s ds]] (map (fn [i s] [i s]) (rest (range)) summaries)]
            [:div {:class "content"}
             [:div.content
              [:h4 {:class "title is-4 is-spaced" :id (str i "-source")}
               [:a {:class "" :href (str "#" i "-source")} "# "]
               [:span {:class ""} (str i ". Conversation")]]
              (md-render s)]
             [:div.content
              [:h5 {:class "title is-5 is-spaced" :id (str i "-message-history")}
               [:a {:class "" :href (str "#" i "-message-history")} "# "]
               [:span {:class ""}  "Messages"]]
              [:ul (for [[d t n f l] (sort-by first ds)]
                     [:li [:div {:class "content"}
                           [:h6 (md-render (str "Message from [[" f " " l "]] (" n ") on " d))]
                           (md-render t)]])]]])])]))))

(defn edit-note [peer {{:keys [chat-id note]} :path-params}]
  (let [conn (ensure-conn peer chat-id)
        body (:note/body (d/entity @conn [:note/title note]))
        edit? (get-in (swap! peer update-in [:transient chat-id note :edit] not) [:transient chat-id note :edit] false)]
    (response
     [:article {:id "note" :class "message"}
      [:div {:class "message-header"}
       [:p note]
       [:button {:class "delete" :hx-post (str "/chats/" chat-id "/notes/" note "/delete") :hx-trigger "click" :hx-target "#note" :hx-swap "outerHTML" :hx-confirm "Are you sure you want to delete this note?"}]]
      [:div {:class "message-body content"}
       (if edit?
         [:textarea {:class "textarea" :rows 20 :name "note" :hx-post (str "/chats/" chat-id "/notes/" note "/edited") :hx-trigger "keyup changed delay:500ms"} body]
         (if body (md-render body) "Note does not exist yet."))
       [:button {:class "button is-primary" :hx-post (str "/chats/" chat-id "/notes/" note "/edit") :hx-target "#note" :hx-swap "outerHTML" :hx-trigger "click"} "Edit"]]])))

(defn edited-note [peer {{:keys [chat-id note]} :path-params
                         :keys [params]}]
  (let [conn (ensure-conn peer chat-id)
        id (or (:db/id (d/entity @conn [:note/title note])) (d/tempid :db.part/user))
        new-body (get params "note")
        links (extract-links new-body)]
    (d/transact conn [(merge
                       {:db/id id
                        :note/title note
                        :note/body new-body}
                       (when (seq links)
                         {:note/link (mapv first (d/q '[:find ?n :in $ [?t ...] :where [?n :note/title ?t]] @conn links))}))])
    {:status 200
     :body "Success."}))

(defn delete-note [peer {{:keys [chat-id note]} :path-params
                         :keys [params]}]
  (let [conn (ensure-conn peer chat-id)]
    (debug "Deleted note entity" (d/transact conn [[:db/retractEntity [:note/title note]]]))
    (response [:div {:id "note" :class "notification"}
               "Note does not exist yet."])))

(defn assistance
  "This interpreter can derive facts and effects through a relational database."
  [[S peer [in out]]]
  ;; pass everything from in to next-in for the next middleware
  ;; and create publication channel for runtime context
  (let [mi (mult in)
        next-in (chan)
        _ (tap mi next-in)
        pub-in (chan)
        _ (tap mi pub-in)
        pi (pub pub-in :type)

       ;; subscriptions for this runtime context
        msg-ch (chan 1000)
        _ (sub pi :ie.simm.runtimes.telegram/message msg-ch)

       ;; do the same in reverse for outputs from below
        prev-out (chan)
        mo (mult prev-out)
        _ (tap mo out)
        pub-out (chan)
        _ (tap mo pub-out)
        po (pub pub-out :type)

        ;; TODO figure out prefix, here conflict if chats/
        routes [["/download/chat/:chat-id/notes.zip" {:get (fn [{{:keys [chat-id]} :path-params}] {:status 200 :body (zip-notes chat-id)})}]
                ["/chats/:chat-id" {:get (partial #'chat-overview peer)}]
                ["/chats/:chat-id/notes/:note" {:get (partial #'view-note peer)}]
                ["/chats/:chat-id/notes/:note/edit" {:post (partial #'edit-note peer)}]
                ["/chats/:chat-id/notes/:note/edited" {:post (partial #'edited-note peer)}]
                ["/chats/:chat-id/notes/:note/delete" {:post (partial #'delete-note peer)}]]]
    (swap! peer assoc-in [:http :routes :assistance] routes)
    ;; we will continuously interpret the messages
    (go-loop-try S [m (<? S msg-ch)]
                 (when m
                   (binding [lb/*chans* [next-in pi out po]]
                     (let [{:keys [msg]
                            {:keys [chat from text]} :msg} m]
                       (try
                         (let [_ (debug "received message" m)
                               firstname (:first_name from)
                               start-time-ms (System/currentTimeMillis)

                               conn (ensure-conn peer (:id chat))
                               _ (debug "conn" conn)

                           ;; 1. add new facts from messages
                               _ (d/transact conn (msg->txs msg))
                               _ (debug "transacted")
                           ;; 2. recent conversation
                               conv (conversation @conn (:id chat) window-size)
                               _ (debug "conversation" conv)
                               msg-count (d/q '[:find (count ?m) .
                                                :in $ ?cid
                                                :where
                                                [?m :message/chat ?c]
                                                [?c :chat/id ?cid]]
                                              @conn (:id chat))
                               _ (debug "message count" msg-count)
                               _ (when (<= (mod msg-count window-size) 1)
                                   (summarize S conn conv chat))


                           ;; 3. retrieve summaries for active links
                               all-links (d/q '[:find [?t ...] :where [_ :conversation/link ?t]] @conn)
                               relevant (<? S (cheap-llm (format "You are given the following links in Wikipedia style brackets [[some entity]]:\n\n%s\n\nList the most relevant links for the following conversation with descending priority.\n\n%s"
                                                                 (str/join ", " (map #(str "[[" % "]]") all-links))
                                                                 conv)))
                               active-links (concat (take 3 (extract-links relevant)) [firstname])

                               summaries (d/q '[:find ?t ?s
                                                :in $ [?t ...]
                                                :where
                                                [?c :note/title ?t]
                                                [?c :note/body ?s]]
                                              @conn (concat active-links (extract-links conv)))
                               _ (debug "active links" #_summaries active-links)]
                           (when (or (= (:type chat) "private")
                                     (.contains text "@simmie"))
                          ;; 4. derive reply
                             (let [assist-prompt (format pr/assistance
                                                         (str/join "\n\n" (map (fn [[t s]] (format "Title: %s\nBody: %s" t s))
                                                                               summaries))
                                                         conv
                                                         (str (java.util.Date.)))
                                   _ (debug "prompt" assist-prompt)
                                   reply (<? S (reasoner-llm assist-prompt))
                                   _ (debug "reply" reply)

                               ;; 5. dispatch
                                   _ (when-let [prompt (second (re-find #"IMAGEGEN\(['\"](.*)['\"]\)" reply))]
                                       (let [url (<? S (image-gen prompt))]
                                         (debug "generated image" url)
                                         (d/transact conn (msg->txs (:result (<? S (send-photo! (:id chat) url)))))))

                                   _ (when-let [title (second (re-find #"ADD_ISSUE\(['\"](.*)['\"]\)" reply))]
                                       (debug "adding issue" title)
                                       (d/transact conn [{:db/id -1 :issue/title title}])
                                       (d/transact conn (msg->txs (:result (<? S (send-text! (:id chat) (str "Added issue: " title)))))))

                                   _ (when-let [title (second (re-find #"REMOVE_ISSUE\(['\"](.*)['\"]\)" reply))]
                                       (if-let [eid (d/q '[:find ?i . :in $ ?t :where [?i :issue/title ?t]] @conn title)]
                                         (do
                                           (debug "removing issue" title eid)
                                           (d/transact conn [[:db/retractEntity eid]])
                                           (d/transact conn (msg->txs (:result (<? S (send-text! (:id chat) (str "Removed issue: " title)))))))
                                         (d/transact conn (msg->txs (:result (<? S (send-text! (:id chat) (str "Could not find issue: " title))))))))

                                   _ (when (or (.contains reply "LIST_ISSUES") (.contains reply "DAILY"))
                                       (debug "listing issues")
                                       (let [issues (d/q '[:find [?t ...] :where [_ :issue/title ?t]] @conn)]
                                         (d/transact conn (msg->txs (:result (<? S (send-text! (:id chat) (str "Issues:\n" (str/join "\n" (map #(format "* %s" %) issues))))))))))

                                   _ (when (.contains reply "SEND_NOTES")
                                       (<? S (send-document! (:id chat) (zip-notes (:id chat))))
                                       (d/transact conn (msg->txs (:result (<? S (send-text! (:id chat) "Done."))))))

                                   _ (when-let [title (second (re-find #"RETRIEVE_NOTE\(['\"](.*)['\"]\)" reply))]
                                       (if-let [body (d/q '[:find ?b . :in $ ?t :where [?n :note/title ?t] [?n :note/body ?b]] @conn title)]
                                         (do
                                           (debug "retrieved note" title body)
                                           (d/transact conn (msg->txs (:result (<? S (send-text! (:id chat) (str "Retrieved " title "\n" body)))))))
                                         (d/transact conn (msg->txs (:result (<? S (send-text! (:id chat) (str "Could not find note: " title))))))))

                                   _ (when (.contains reply "CHAT_HOMEPAGE")
                                       (debug "chat homepage")
                                       (let [#_#_issues (d/q '[:find [?t ...] :where [_ :note/title ?t]] @conn)]
                                         (d/transact conn (msg->txs (:result (<? S (send-text! (:id chat) (str (format "%s/chats/%s" base-url (:id chat))))))))))


                                   _ (if (or (.contains reply "QUIET") (.contains reply "IMAGEGEN") (.contains reply "LIST_ISSUES") (.contains reply "ADD_ISSUE")
                                             (.contains reply "REMOVE_ISSUE") (.contains reply "SEND_NOTES") (.contains reply "RETRIEVE_NOTE") (.contains reply "CHAT_HOMEPAGE"))
                                       (debug "No reply necessary")
                                       (let [reply (if-let [terms (second (re-find #"WEBSEARCH\(['\"](.*)['\"]\)" reply))]
                                                     (let [url (<? S (search terms))
                                                           body (<? S (extract-body url))
                                                           prompt (format pr/search (conversation @conn (:id chat) 5) body)
                                                           reply (<? S (cheap-llm prompt))]
                                                       (debug "conducting web search" terms)
                                                       (str reply "\n" url))
                                                     (.replace reply "DAILY" ""))
                                             send-time-ms (System/currentTimeMillis)
                                             min-response-time 3000
                                             too-fast-by (max 0 (- min-response-time (- send-time-ms start-time-ms)))
                                             _ (debug "too fast by" too-fast-by)
                                             _ (<? S (timeout too-fast-by))

                                             reply (if (<= msg-count 1)
                                                     (format "Hello! I am Simmie and am here to help you with your tasks or just to have fun. You can ask me for assistance at any time. I can also summarize conversations and help you with your notes. I can also track issues, do web searches, summarize links you send to me or generate images. Check out the website for this chat at %s/chats/%s" base-url (:id chat))
                                                     reply)
                                             reply-msg (<? S (send-text! (:id chat) reply))
                                             _ (debug "reply-msg" reply-msg)
                                             _ (d/transact conn (msg->txs (:result reply-msg)))]))])))
                         (catch Exception e
                           (let [error-id (uuid)]
                             (error "Could not process message(" error-id "): " m e)
                             (<? S (send-text! (:id chat) (str "Sorry, I could not process your message. Error: " error-id))))))))
                   (recur (<? S msg-ch))))
    ;; Note that we pass through the supervisor, peer and new channels for composition
    [S peer [next-in prev-out]]))


;; unportd logic from first prototype
(comment

 (h/defhandler bot-api
    (h/command-fn "start" (fn [{{id :id :as chat} :chat}]
                            (debug "Bot joined new chat: " chat)
                            (notify! id "Welcome! You can always get help with /help.")))

    (h/command-fn "help" (fn [{{id :id :as chat} :chat}]
                           (notify! id "konstruktiv is here to help!\n\nIt automatically transcribes audio messages to text. Try the following commands /daily, /assist, /summarize, /search, /imitate username.")))

    (h/command-fn "search" (fn [{:keys [text chat] {id :id} :chat}]
                             (let [terms (->> (str/split (str/trim text) #"\s+") rest (str/join " "))
                                   terms (if-not (empty? (str/trim terms))
                                           terms
                                           (openai/chat "gpt-3.5-turbo" (format "%s\n\n\nDerive a web search with a few relevant terms (words) from the previous conversation. Only return the terms and nothing else.\n" (conversation id))))]
                               (debug "searching for terms" terms)
                               (go
                                 (e/with-chrome-headless driver
                                   (notify! id (summarize-web-search driver terms)))))))

    (h/command-fn "add" (fn [{:keys [text chat] {id :id} :chat :as message}]
                          (try
                            (let [args (->> (str/split (str/trim text) #"\s+") rest)]
                              (case (first args)
                                "issue" (do (add-issue! conn (str/join " " (rest args)) id)
                                            (notify! id "Added issue." {:reply_to_message_id (:message_id message)})))
                              "Thanks!")
                            (catch Exception _
                              "Thanks!"))))

    (h/command-fn "remove" (fn [{:keys [text chat] {id :id} :chat :as message}]
                             (try
                               (let [args (->> (str/split (str/trim text) #"\s+") rest)]
                                 (case (first args)
                                   "issue" (do (remove-issue! conn (str/join " " (rest args)) id)
                                               (notify! id "Removed issue." {:reply_to_message_id (:message_id message)})))
                                 "Thanks!")
                               (catch Exception _
                                 "Thanks!"))))

    (h/command-fn "schedule" (fn [{:keys [text chat] {id :id} :chat :as message}]
                               (try
                                 (let [args (->> (str/split (str/trim text) #"\s+") rest)]
                                   (case (first args)
                                     "issue" (do (schedule-issue! conn (str/join " " (rest args)) id)
                                                 (notify! id "Scheduled issue at " {:reply_to_message_id (:message_id message)})))
                                   "Thanks!")
                                 (catch Exception _
                                   "Thanks!"))))

    (h/command-fn "list" (fn [{:keys [text chat] {id :id} :chat}]
                           (try
                             (let [args (->> (str/split (str/trim text) #"\s+") rest)]
                               (case (first args)
                                 "issue" (notify! id (list-issues conn id)))
                               "Thanks!")
                             (catch Exception _
                               "Thanks!"))))

    (h/command-fn "daily" (fn [{{id :id :as chat} :chat}]
                            (let [chat-chan (chan)]
                              (sub chat-pub id chat-chan)
                              (debug "daily exchange: " chat)
                              (daily-agenda chat chat-chan)
                            ;; TODO don't block thread!
                              #_((good-morning chat (m/watch !input))
                                 #(debug ::morning-success %)
                                 #(debug ::morning-failure %)))))

    (h/command-fn "assist"  (fn [{{id :id :as chat} :chat}]
                              (d/transact conn [[:db/add [:chat/id (long id)] :chat/under-assistance? true]])
                              (println "Help was requested in " chat)
                              (notify! id "Assistance is now turned on. You can pause it again with /pause.")))

    (h/command-fn "pause" (fn [{{id :id :as chat} :chat}]
                            (d/transact conn [[:db/add [:chat/id (long id)] :chat/under-assistance? false]])
                            (debug "Bot was paused in " chat)
                            (notify! id "Pausing for now. You can turn it on again with /assist.")))

    (h/command-fn "summarize" (fn [{{id :id :as chat} :chat}]
                                (let [summary (openai/chat "gpt-3.5-turbo" (format pr/summarization (conversation id)))]
                                  (debug "Generated summary: " summary)
                                  (notify! id
                                           #_{:reply_markup {:inline_keyboard [[{:text "Good" :callback_data "good"}
                                                                                {:text "Bad" :callback_data "bad"}]]}}
                                           summary))))

    (h/command-fn "imitate" (fn [{:keys [text] {id :id :as chat} :chat :as message}]
                              (let [username (->> (str/split (str/trim text) #"\s+")
                                                  second)
                                    _ (debug "Imitating " username)
                                    conversation (conversation id)
                                    _ (println conversation)
                                    prompt (format pr/clone-prompt username conversation)
                                    response (openai/chat "gpt-4-1106-preview" #_"gpt-3.5-turbo" prompt)]
                                (notify! id response))))

    (h/message-fn  (fn [{:keys [chat voice] :as message}]
                     (debug "Received message:" message)
                     (try
                       (let [chat-id (:id chat)
                             message (if-not voice
                                       message
                                       (let [transcript (create-transcript! voice)]
                                         (debug "created transcript" transcript)
                                         (notify! chat-id transcript {:reply_to_message_id (:message_id message)})
                                         (assoc message :text transcript)))]
                         (d/transact conn (msg->txs message))
                         #_(reset! !input message)
                         (put! in-chan message)
                         (when (get (d/entity @conn [:chat/id chat-id]) :chat/under-assistance?)
                           (assist! chat-id)))
                       (catch Exception e
                         (warn "Could not process message" message e)))))) 

 (defn daily-agenda [chat chat-chan]
  (go
    (let [{:keys [id username]} chat
          affects-agenda? #(let [res (openai/chat "gpt-4-1106-preview" #_"gpt-3.5-turbo" %)]
                             (debug "affects agenda" res)
                             (when-not (.contains res "NOOP") res))

          agenda (m/? (get-agenda username))

          _ (notify! id (format "Hey %s! How are you doing?" username))
          _reply-how-are-you-doing (<! chat-chan)
          ;; be polite
          _ (notify! id (openai/chat "gpt-3.5-turbo" (format "%s\n\nGiven this recent conversation, briefly reply to the last message directly." (conversation id))))

          ;; weather affects agenda
          conv (conversation id)
          _ (debug "conversation" conv)
          agenda-as-json (json/write-value-as-string (select-keys agenda #{:weather :agenda}))
          weather-affects-agenda? (affects-agenda? (format pr/weather-affects-agenda conv agenda-as-json username))
          _ (when weather-affects-agenda?
              (notify! id weather-affects-agenda?)
              (<! (go-loop [in (<! chat-chan)]
                    (let [conversation (conversation id)
                          _ (debug "proceeding in weather discussion" in)
                          not-done? (let [res (openai/chat "gpt-4-1106-preview" #_"gpt-3.5-turbo" (format "%s\n\nAre we done discussing how the weather affects the agenda in this conversation? Answer only with YES or NO." conversation))]
                                      (.contains res "NO"))]
                      (when not-done? 
                        (notify! id (openai/chat "gpt-4-1106-preview" (format "%s\n\nGiven the preceeding conversation, make a suggestion how to fix the agenda." conversation)))
                        (recur (<! chat-chan)))))))

          ;; emails affect agenda
          agenda-as-json (json/write-value-as-string (select-keys agenda #{:new-emails :agenda}))
          emails-affect-agenda? (affects-agenda? (format pr/emails-affect-agenda (conversation id) agenda-as-json username))

          _ (when emails-affect-agenda?
              (notify! id emails-affect-agenda?)
              (<! (go-loop [in (<! chat-chan)]
                    (let [conversation (conversation id)
                          _ (debug "proceeding in email discussion" in)
                          not-done? (let [res (openai/chat "gpt-4-1106-preview" #_"gpt-3.5-turbo" (format "%s\n\nAre we done discussing how the emails affect the agenda in this conversation? Answer only with YES or NO." conversation))]
                                      (.contains res "NO"))]
                      (when not-done? (recur (<! chat-chan)))))))]
      (notify! id "Thanks! That's all that there is discuss for now.")
      (close! chat-chan)))) 
  
  )