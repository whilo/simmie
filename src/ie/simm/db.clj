(ns ie.simm.db
  (:require [clojure.java.io :as io]
            [datahike.api :as d]
            [clojure.string :as str]))

(def default-schema (read-string (slurp "resources/default_schema.edn")))

(defn ensure-conn [peer chat-id]
  (or (get-in @peer [:conn chat-id])
      (let [;; ensure "databases directory exists and creating it if not"
            path (str "databases/" chat-id)
            _ (io/make-parents path)
            cfg {:store {:backend :file :scope "simm.ie/chats" :path path}}
            conn
            (try
              (let [cfg (d/create-database cfg)
                    conn (d/connect cfg)]
                (d/transact conn default-schema)
                conn)
              (catch Exception _
                (d/connect cfg)))]
        #_(d/transact conn default-schema)
        (swap! peer assoc-in [:conn chat-id] conn)
        conn)))

(defn conversation [db chat-id window-size]
  (->>
   (d/q '[:find ?d ?f ?l ?n ?t
          :in $ ?chat
          :where
          [?c :chat/id ?chat]
          [?e :message/chat ?c]
          [?e :message/text ?t]
          [?e :message/date ?d]
          [?e :message/from ?u]
          [(get-else $ ?u :from/username "") ?n]
          [(get-else $ ?u :from/first_name "") ?f]
          [(get-else $ ?u :from/last_name "") ?l]]
        db chat-id)
   (sort-by first)
   (take-last window-size)
   (map (fn [[d f l n t]] (str d " " f " " l " (" n "): " (str/replace t #"\n" " "))))
   (str/join "\n")))

(defn extract-links [text]
  (vec (distinct (map second (re-seq #"\[\[([^\[\]]+)\](\[.+\])?\]" text)))))

(defn msg->txs [message]
  (let [{:keys [message_id from chat date text]} message
        tags (when text (extract-links text))]
    (vec
     (concat
      (when from
        [(let [{:keys [id is_bot first_name last_name username language_code]} from]
           (merge
            {:from/id (long id)
             :from/is_bot is_bot}
            (when username
              {:from/username username})
            (when language_code
              {:from/language_code language_code})
            (when first_name
              {:from/first_name first_name})
            (when last_name
              {:from/last_name last_name})))])
      (when chat
        [(let [{:keys [id first_name username type title all_members_are_administrators]} chat]
           (merge
            {:chat/id (long id)
             :chat/type type}
            (when username
              {:chat/username username})
            (when first_name
              {:chat/first_name first_name})
            (when title
              {:chat/title title})
            (when all_members_are_administrators
              {:chat/all_members_are_administrators all_members_are_administrators})))])
      [(merge
        {:message/id (long message_id)
         :message/from [:from/id (long (:id from))]
         :message/chat [:chat/id (long (:id chat))]
         :message/date (java.util.Date. (long (* 1000 date)))}
        (when text
          {:message/text text})
        (when (seq tags)
          {:message/link tags}))]))))

(def window-size 10)

