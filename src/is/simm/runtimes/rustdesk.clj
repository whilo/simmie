(ns is.simm.runtimes.rustdesk
  "This runtime provides obligations from screensharing.
   
   Languages: screen
   It is a source runtime that does not discharge any additional outputs and does not handle other inputs."
  (:require [is.simm.peer :as peer]
            [is.simm.http :refer [response]]
            [is.simm.website :refer [md-render default-chrome base-url]]
            [is.simm.runtimes.openai :refer [text-chat chat]]
            [is.simm.parse :refer [parse-json]]
            [clojure.core.async :refer [timeout put! chan pub sub close! take! poll!] :as async]
            [superv.async :refer [go-try S <? <?? go-loop-try put? go-for]]
            [taoensso.timbre :refer [debug]]
            [missionary.core :as m]
            [datahike.api :as d]

            [clojure.data :refer [diff]]
            [clojure.java.io :as io])
  (:import [java.nio.file Files Paths]
           [java.util Base64]))


(defn >! "Puts given value on given channel, returns a task completing with true when put is accepted, of false if port was closed."
  [c x] (doto (m/dfv) (->> (async/put! c x))))

(defn <! "Takes from given channel, returns a task completing with value when take is accepted, or nil if port was closed."
  [c] (doto (m/dfv) (->> (async/take! c))))

(def base-screenshot-folder "/home/ubuntu/simmis/files/screenshots") ;; Base folder for screenshots

;; Function to list all screenshots in the screenshot-folder
(defn all-screenshots [screenshot-folder]
  (->> (file-seq (io/file screenshot-folder))
       (filter #(re-matches #"frames_.*\.jpg" (.getName %)))
       (map #(.getPath %))
       set))

(defn encode-image [image-path]
  (with-open [input-stream (io/input-stream image-path)]
    (let [image-bytes (.readAllBytes input-stream)]
      (.encodeToString (Base64/getEncoder) image-bytes))))

(defn frame-iterator
  "Iterates over all screenshots in the screenshot-folder and updates every 60 seconds. Properly handles backpressure."
  []
  (m/ap
   (loop [screenshots #{}
          to-emit #{}
          first-run? true]
     (if (empty? to-emit)
       ;; if there is nothing to emit, we need to check for new screenshots
       (let [new-screenshots (all-screenshots base-screenshot-folder)
             [_ delta _] (diff screenshots new-screenshots)
             delta (set delta)]
         (when-not first-run?
           (m/? (m/sleep (* 60 1000))))
         (recur new-screenshots delta false))
       ;; sequentially emit the screenshots
       (let [screenshot (first to-emit)]
         (m/amb screenshot (recur screenshots (disj to-emit screenshot) false)))))))

(def schema [{:db/ident :screen/id
              :db/valueType :db.type/string
              :db/cardinality :db.cardinality/one}
             {:db/ident :screenshot/path
              :db/valueType :db.type/string
              :db/unique :db.unique/identity
              :db/cardinality :db.cardinality/one}
             {:db/ident :screenshot/transcript
              :db/valueType :db.type/string
              :db/cardinality :db.cardinality/one}
             {:db/ident :screenshot/created
              :db/valueType :db.type/instant
              :db/cardinality :db.cardinality/one}])

(defn extract-timestamp [s]
  (let [m (re-matches #".+frames_(\d+)\.jpg" s)]
    (java.util.Date. (* 1000 (Long/parseLong (m 1))))))

(defn extract-screen [s]
  (let [m (re-matches #".+/(\d+)/frames_.+\.jpg" s)]
    (m 1)))

(defn ensure-conn [peer screen-id]
  (or (get-in @peer [:conn screen-id])
      (let [path (str "databases/screens/" screen-id)
            _ (io/make-parents path)
            cfg {:store {:backend :file :scope "simm.is/screens" :path path}
                 :allow-unsafe-config true}
            conn
            (try
              (let [cfg (d/create-database cfg)
                    conn (d/connect cfg)]
                (d/transact conn schema)
                conn)
              (catch Exception _
                (d/connect cfg)))]
        (swap! peer assoc-in [:conn screen-id] conn)
        conn)))

(def transcripts (read-string (slurp "/home/ubuntu/simmis/transcriptions.edn")))

(def screenshot-prompt "You are a note taker for a screen transcription tool. Describe all the visual information as precisely as possible such that it can be retained even if the image is lost. Describe the screen hierarchically. If a browser is visible, write down the URL that is open. Write down *all* text you can see in the respective context. Summarize your observations in the end and try to infer what the user is doing on a high-level.")

(defn add-screenshot! [conn file-name]
  (m/sp
   (let [created (extract-timestamp file-name)
         screen (extract-screen file-name)
         db @conn]
     (when-not (d/entity db [:screenshot/path file-name])
       (debug "adding screenshot" file-name)
       (try
         (d/transact! conn [{:screenshot/path file-name
                             :screen/id screen
                             :screenshot/created created
                             :screenshot/transcript (or (transcripts file-name)
                                                        (m/? (<! (chat "gpt-4o"
                                                                       [{:type "text"
                                                                         :text screenshot-prompt}
                                                                        {:type "image_url" :image_url {:url (str "data:image/jpeg;base64," (encode-image file-name))}}]))))}])
         (catch Exception e
           (debug "error adding screenshot" file-name e)))))))

(defn main [peer]
  (let [<x (frame-iterator)]
    (m/reduce (fn [_ x]
                (when x
                  (let [conn (ensure-conn peer (extract-screen x))]
                    (debug "new screenshot" conn x)
                    (m/? (add-screenshot! conn x)))))
              nil <x)))


(comment

  (defn test-iterator [<frames]
    (m/reduce (fn [_ x]
                (when x
                  (prn "new screenshot" x)))
              nil
              <frames))

  ((test-iterator (frame-iterator "454345782"))
   prn
   prn)

  (ensure-conn my-peer "491680819")

  (d/q '[:find (count ?e) :where [?e :screen/id "491680819"]] @(ensure-conn my-peer "491680819"))



  ((frame-iterator "454345782")

   prn
   prn)


  (+ 1 2)


  (def my-conn
    (get-in @my-peer [:conn "454345782"]))

  (d/q '[:find (count ?e) :where [?e :screen/id "454345782"]] @my-conn)



  (add-screenshot! my-conn "/home/ubuntu/screenshots/454345782/frames_1728978828.jpg")

  (extract-screen "/home/ubuntu/screenshots/454345782/frames_1728978828.jpg")

  ((all-screenshots "/home/ubuntu/screenshots/454345782") "/home/ubuntu/screenshots/454345782/frames_1729024426.jpg")

  (dispose!)

  (def term-ch (chan))

  (process-frames "454345782" term-ch)

  (put! term-ch :done)

  (def test-screenshots (sort (all-screenshots "/home/ubuntu/simmis/files/screenshots/")))

  (first test-screenshots)

  (last test-screenshots)

  (encode-to-base64 (read-file-as-byte-array (first test-screenshots)))

  (require '[is.simm.runtimes.openai :as openai])

  (require '[superv.async :refer [<?? S]])
  (require '[clojure.core.async :refer [<!!]])

  (def last-screenshots (mapv encode-image (take-last 1 test-screenshots)))

  (map count last-screenshots)

  (subs (first last-screenshots) 100)

  (def test-query
    (<!!  (openai/chat "gpt-4o"
                       (vec (concat [{:type "text"
                                      :text "You are a note taker for a screen transcription tool. Describe all the visual information as precisely as possible such that it can be retained even if the image is lost. Describe the screen hierarchically. If a browser is visible, write down the URL that is open. Write down *all* text you can see in the respective context. Summarize your observations in the end and try to infer what the user is doing on a high-level."}]
                                    (for [sp last-screenshots]
                                      {:type "image_url" :image_url {:url (str "data:image/jpeg;base64," sp)}}))))))

  (def transcriptions
    (->> (all-screenshots "/home/ubuntu/simmis/files/screenshots/")
         sort
         (take-last 100)
         (map (fn [s]
                (prn "processing" s)
                [s (openai/chat "gpt-4o"
                                [{:type "text"
                                  :text "You are a note taker for a screen transcription tool. Describe all the visual information as precisely as possible such that it can be retained even if the image is lost. Describe the screen hierarchically. If a browser is visible, write down the URL that is open. Write down *all* text you can see in the respective context. Summarize your observations in the end and try to infer what the user is doing on a high-level."}
                                 {:type "image_url" :image_url {:url (str "data:image/jpeg;base64," (encode-image s))}}])]))
         (into {})))

  (<!! (async/into {} (go-for S [[s c] transcriptions] [s (<? S c)])))

  (def result *1)

  (count result)

  (spit "/home/ubuntu/simmis/transcriptions.edn" (pr-str result))

  (def transcriptions (read-string (slurp "/home/ubuntu/simmis/transcriptions.edn")))

  (count (all-screenshots "/home/ubuntu/simmis/files/screenshots/"))

  (def user "The user is a seasoned Clojure developer who has build distributed databases and has a PhD in machine learning. He is not very strong in building user interfaces and is currently still alone, even though he would like to start a company.")

  (def goal "The current project is about building an interactive AI assistant that watches your screen continuously. It is build with functional reactive programming and has a web interface. It retains a memory in form of linked notes (like Notion, Roam) and can talk to the user through Telegram chats beside the web interface. The goal is to build a predictive model for user's behaviour and help them focus and speed up.")

  (def gpt-suggestion
    (<!! (openai/chat "gpt-4o-mini" [{:type "text" :text (format "User description:\n======\n%s\n======\nThe user has the following goal:\n======\n%s\n======\nGiven the following last screenshot descriptions (60 secs apart). Make specific suggestions to help the user, assume they are a professional in their work.\n======\n%s\n======\n"
                                                                 user
                                                                 goal
                                                                 (pr-str (take-last 5 (sort-by first transcriptions))))}])))


  (ex-data test-query)

  ;; same but read as byte array


  ;; encode screenshot as base64




  (encode-screenshot (first test-screenshots))

  (->> (d/q '[:find ?c ?t
              :where
              [?i :screenshot/created ?c]
              [?i :screenshot/transcript ?t]] @my-conn)
       (sort-by first)
       reverse
       (map (fn [[c t]] [c (remove-headline (last (.split t "\n\n")))])))
  )

(defn remove-headline [s]
  (let [lines (.split s "\n")]
    (.replace
     (if (or (.startsWith (first lines) "#")
             (.startsWith (first lines) "*"))
       (apply str (rest lines))
       (apply str lines))
     "- " "")))

(def question-prompt "You are taking to the user with biography:\n======\n%s\n======\nThe user has the following goal:\n======\n%s\n======\nYou are given the following last screenshot descriptions (60 secs apart).\n======\n%s\n======\nAnswer the following question:\n======\n%s\n======\n")

(def related-work-prompt "You are taking to the user with biography:\n======\n%s\n======\nThe user has the following goal:\n======\n%s\n======\nYou are given the following last screenshot descriptions (60 secs apart).\n======\n%s\n======\nDescribe possible related work searches in a single sentence search query objective for the user to chose. Reply as a JSON array of strings.")

(def biography "The user is a seasoned Clojure developer who has build distributed databases and has a PhD in machine learning.")

(def goal "The goal is to build an AI assistant that watches the screen through a remote desktop interface and makes predictions about what the user is going to need or do next. It also makes suggestions to streamline the workflow.")

(defn ask-question [peer {{:keys [screen-id]} :path-params {:strs [question goal biography]} :params}]
  (let [conn (ensure-conn peer screen-id)
        _ (prn "Asking question: " question goal)
        recent-screens (->> (d/q '[:find ?c ?t
                                   :where
                                   [?i :screenshot/created ?c]
                                   [?i :screenshot/transcript ?t]] @conn)
                            (sort-by first)
                            reverse
                            (map second)
                            (take 100)
                            (apply str))
        #_(prn "Recent screens: " recent-screens)
        reply-ch (chat "gpt-4o-mini" [{:type "text" :text (format question-prompt biography goal recent-screens question)}])
        reply (<?? S reply-ch)
        _ (prn "reply: " reply)
        related-work-ch (chat "gpt-4o-mini" [{:type "text" :text (format related-work-prompt biography goal recent-screens)}])
        related-work (<?? S related-work-ch)
        _ (prn "related work: " related-work)
        related-work-searches (parse-json related-work)]
    (prn related-work-searches)
    (response
     [:div {:class "container" :id "assistant"}
      [:form {:class "box"}
       [:div.field
        [:label.label "Biography"]
        [:div.control
         [:textarea {:class "textarea assistant" :rows 5 :placeholder "Biography" :id "biography" :name "biography"} biography]]]
       [:div.field
        [:label.label "Goal"]
        [:div.control
         [:textarea {:class "textarea assistant" :rows 5 :placeholder "Your goal." :id "goal" :name "goal"} goal]]]
       [:div.field
        [:label.label "Question"]
        [:div.control
         [:input {:class "input is-primary assistant" :type "input" :placeholder "Ask a question..." :id "question" :name "question"}]]]
       [:button {:class "button is-primary" :hx-post (str "/screen/" screen-id "/ask") :hx-target "#assistant" :hx-trigger "click" :hx-swap "outerHTML" :hx-include ".assistant" :hx-on "click: this.disabled = true"} "Ask"]
       [:div {:class "box" :style "margin-top: 10px;"}
        [:div.field
         [:label.label "Reply"]
         [:div {:class "content" :id "reply"} (md-render reply)]]]]
      [:div.container
       [:h4 {:class "title is-4 is-spaced"}
        [:span {:class ""} "Related work search suggestions"]]
       [:div {:class "content"}
        [:ul
         (for [s related-work-searches]
           [:li [:a {:href (str "/screen/" screen-id "/related-work/" s)} s]])]]]])))


(defn screen-overview [peer {{:keys [screen-id]} :path-params}]
  (let [conn (ensure-conn peer screen-id)
        title "Noname screen"
        screenshot-count (d/q '[:find (count ?e) . :in $ ?screen-id :where [?e :screen/id ?screen-id]] @conn screen-id)
        ;title (or (:chat/title (d/entity @conn [:chat/id (Long/parseLong screen-id)])) "Noname chat")
        ;; notes (->> (d/q '[:find ?t :where [?n :note/title ?t]] @conn)
        ;;            (map first)
        ;;            sort)
        ]
    (response
     (default-chrome title
                     [:div {:class "container"}
                      [:nav {:class "breadcrumb" :aria-label "breadcrumbs"}
                       [:ul {}
                        [:li [:span #_{:href "/#"} [:span {:class "icon is-small"} [:i {:class "bx bx-circle"}]] [:span "Systems"]]]
                        [:li.is-active
                         [:a {:href (str "/screens/" screen-id)}
                          [:span {:class "icon is-small"} [:i {:class "bx bx-chat"}]]
                          [:span title]]]]]
                      [:div {:class "container"}
                       [:h2 {:class "title is-2 is-spaced" :id "bots"}
                        [:a {:class "" :href "bots"} [:i {:class "bx bx-bot"}]]
                        [:span {:class ""} "Screen assistant"]]
                       [:div {:class "content"}
                        [:p (format "There are %d screenshots from this screen." screenshot-count)]]
                       [:div {:class "container" :id "assistant"}
                        [:form {:class "box"}
                         [:div.field
                          [:label.label "Biography"]
                          [:div.control
                           [:textarea {:class "textarea assistant" :rows 5 :placeholder "Biography" :id "biography" :name "biography"} biography]]]
                         [:div.field
                          [:label.label "Goal"]
                          [:div.control
                           [:textarea {:class "textarea assistant" :rows 5 :placeholder "Your goal." :id "goal" :name "goal"} goal]]]
                         [:div.field
                          [:label.label "Question"]
                          [:div.control
                           [:input {:class "input is-primary assistant" :type "input" :placeholder "Ask a question..." :id "question" :name "question"}]]]
                         [:button {:class "button is-primary" :hx-post (str "/screen/" screen-id "/ask") :hx-target "#assistant" :hx-trigger "click" :hx-swap "outerHTML" :hx-include ".assistant" :hx-on "click: this.disabled = true"} "Ask"]
                         [:div {:class "box" :style "margin-top: 10px;"}
                          [:div.field
                           [:label.label "Reply"]
                           [:div {:class "content" :id "reply"} "-"]]]]]
                       [:div {:class "container" :id "screenshots" :style "margin-top: 10px;"}
                        [:div.box
                         [:div.content
                          [:h4 {:class "title is-4 is-spaced"}
                           [:a {:class "" :href "screenshots"} "# "]
                           [:span {:class ""} "Recent screenshots"]]
                          (let [screenshots  (->> (d/q '[:find ?c ?t ?p
                                                         :where
                                                         [?i :screenshot/created ?c]
                                                         [?i :screenshot/transcript ?t]
                                                         [?i :screenshot/path ?p]] @conn)
                                                  (sort-by first)
                                                  reverse
                                                  (take 100)
                                                  (map (fn [[c t p]] [c
                                                                      t #_(remove-headline (last (.split t "\n\n")))
                                                                      (.replace (.replace p "/home/ubuntu/simmie" "")
                                                                                "/home/ubuntu/simmis" "")])))]
                            (if (seq screenshots)
                              [:ul
                               (map (fn [i [c t p]]
                                      [:li {:class "highlight"} #_[:a {:href (str "/screen/" screen-id "/screenshots/" c)} c]
                                       [:p {} (md-render t)]
                                       [:img {:src p :width "100%"}]])
                                    (range)
                                    screenshots)]
                              "No screenshots."))]]]]]))))

(defn rustdesk
  ([[S peer [in out]]]
   (let [routes [["/screen/:screen-id" {:get (partial #'screen-overview peer)}]
                 ["/screen/:screen-id/ask" {:post (partial #'ask-question peer)}]]]

     (def dispose!
       ((main peer) #(prn ::success %) #(prn ::error %)))

     (def my-peer peer)
     (peer/add-routes! peer :screen routes)
     [S peer [in out]])))
