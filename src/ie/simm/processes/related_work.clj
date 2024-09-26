(ns ie.simm.related-work
  (:require [datahike.api :as d]
            [superv.async :refer [S go-try <? put?]]
            [clojure.core.async :refer [chan pub sub mult tap]]
            [ie.simm.runtimes.openai :as openai]
            [ie.simm.runtimes.brave :as brave]
            [ie.simm.runtimes.etaoin :as etaoin]
            [etaoin.api :as e]
            [babashka.http-client :as http]
            [pdfboxing.text :as text]
            [remus :refer [parse-url parse-file] :as remus]
            [jsonista.core :as json]
            [taoensso.timbre :as timbre]
            [clojure.java.io :as io]
            [clojure.string :as str]

            [hickory.core :as hickory]
            [hickory.select :as select]
            [clojure.edn :as edn]))


(defn extract-arxiv-paper [title]
  (try
    (let [query (str "site:arxiv.org " title " pdf") #_(.replace (openai/chat "gpt-5o-mini" venture) "\"" "")
          urls (->> (brave/search-brave query) :web :results (map :url) (filter #(.startsWith % "https://arxiv.org/pdf")))
          ;; arxiv-html (when (seq urls) (etaoin/extract-body (first urls)))
          rand-path (str "/tmp/" (java.util.UUID/randomUUID) ".pdf")
          _ (io/copy (:body  (http/get (.replace (first urls) ".pdf" "") {:as :stream}) ) (io/file rand-path))
          text (text/extract rand-path)
          references (when text
                       (mapv (fn [[author title]] {:author author :title title})
                             (-> (openai/chat "gpt-4o-mini" 
                                              (format "Do not reply, only extract the first author family name and title for each paper from the reference section of the following paper as a JSON vector of string tuple vectors: %s" 
                                                      text))
                                 (.replace "```json" "")
                                 (.replace "```" "")
                                 read-string)))
          ;; extract github urls with regex
          github-urls (->> text 
                           (re-seq #"https://github.com/[^ ]+")
                           (map #(.replace % "/\n" "/"))
                           (map #(re-find #"https://github.com/[^/]+/[^/\.\n]+" %)))]
      {:query query 
       :urls urls 
       :text text 
       :references references
       :github-urls github-urls})
    (catch Exception e
      (timbre/error e)
      (Thread/sleep 1000) ;; hack to deal with brave rate limiting
      nil)))

(comment

  (def gsdm-arxiv (extract-arxiv-paper "Graphically Structured Diffusion Model"))

  (openai/chat "gpt-4o" (format "Given the following github links, provide a regular expression that extracts them while avoiding the extracted PDF clutter at the end. Be aware that there are new lines sometimes interspersed, try to extract the correct links also in these cases. \n========\n%s\n========\n"
                                (str/join "\n" (filter identity (map :github-urls references)))))

  (:github-urls gsdm-arxiv)

  (def references (mapv (fn [{:keys [author title]}]
                          (extract-arxiv-paper (str author " " title)))
                        (:references gsdm-arxiv)))

  (map :github-urls references)


  (openai/chat "gpt-4o-mini"
               (format "Given the following related work \n========\n%s\n========\n Describe how the following paper is situated in this context: \n========\n%s\n========\n"
                       (str/join "\n\n\n" (for [r references
                                                :let [text (:text r)]
                                                :when text
                                                :let [text (subs text 0 (min 10000 (count text)))]]
                                            text))
                       (:text gsdm-arxiv)))

  (openai/chat "gpt-4o-mini"
               (format "Given the following paper \n========\n%s\n========\n Which top 5 references do you want to look up to evaluate the novelty of the paper. Do not reply, return a JSON list of title strings only. Reference titles: \n========\n%s\n========\n"
                       (:text gsdm-arxiv)
                       (str/join "\n" (map :title (:references gsdm-arxiv)))))

  (:references gsdm-arxiv)


  (http/get (format "https://libgen.is/search.php?req=%s&column=title" (.replace "Machine Learning - an Algorithmic Perspective" " " "+")))


  (io/copy (:body  (http/get "https://download.library.lol/main/263000/be9884dfad35cf3c240b774e8810413b/%28BBC%29%20Susie%20Conklin%2C%20Sue%20Birtwistle%20-%20The%20Making%20of%20Pride%20and%20Prejudice-Penguin%20%28Non-Classics%29%20%282003%29.pdf" {:as :stream})) (io/file "/tmp/test.pdf"))



  )

        
(defn search-libgen [text]
  (let [search-page (http/get (format "https://libgen.is/search.php?req=%s" (.replace text " " "+")))
        soup (hickory/as-hickory (hickory/parse (:body search-page)))
        information-table (nth (->> soup (select/select (select/tag :table))) 2)
        urls (for [row (select/select (select/tag :tr) information-table)
                       td (select/select (select/tag :td) row)
                       a (select/select (select/tag :a) td)
                       :when (= (:content a) ["[1]"])]
                   (-> a :attrs :href))]
    urls))

(defn extract-libgen [url]
  (let [page (http/get url)
        soup (hickory/as-hickory (hickory/parse (:body page)))
        title (->> soup (select/select (select/tag :h1)) first :content)
        metadata (->> soup (select/select (select/tag :p)) (map (comp first :content)))
        download-link (->> soup (select/select (select/tag :a)) first :attrs :href)]
        {:title title
         :metadata (vec (filter string? metadata))
         :download-link download-link}))
            
(comment
  (def libgen-urls (search-libgen "norvig artificial intelligence"))


  (def extracted (mapv extract-libgen libgen-urls))

  (def extracted-pdfs (filter #(re-find #"pdf" (:download-link %)) extracted))

  (future
    (let [rand-path (str "/tmp/" (java.util.UUID/randomUUID) ".pdf")]
      (io/copy (:body  (http/get (:download-link (first extracted-pdfs)) {:as :stream})) (io/file rand-path))
      (prn "downloaded " rand-path)))

  ;; extract text from pdf
  (def book-text (text/extract "/tmp/test.pdf"))

  (count book-text)

  (openai/chat "gpt-4o" (format "What does the following book say about the relationship between P and NP and how this relates to inference algorithms? Reply with pointers to the specific sections and quotes where suitable.\n======\n%s\n======\n"
                                (subs book-text 0 400000)))

  (def important-algorithm-reply
    (openai/chat "gpt-4o-mini" (format "What is the most important algorithm in the following book? Please try to rewrite it in Clojure and return a single, self-contained, complete namespace with test invocations in a comment block.\n======\n%s\n======\n"
                                       (subs book-text 0 400000))))

  (println important-algorithm-reply)

  (spit "/tmp/gpt-4o-reply.clj" important-algorithm-reply)


  ;; extract a clojure markdown block from the reply
  (def clojure-block (re-find #"```clojure\n(.|\n)*```" important-algorithm-reply))

  (re-find #"```clojure\n(.|\n)*```" "```clojure\n(defn foo []\n  (println \"Hello, World!\"))\n```")

  (require '[clojure.edn :as edn])

  (def clojure-src (first (.split (second (.split important-algorithm-reply "```clojure\n")) "```")))

  (import '[java.io StringReader PushbackReader])

  (defn read-forms-from-string [s]
    (let [reader (PushbackReader. (StringReader. s))]
      (loop [forms []]
        (let [form (read reader false nil)]
          (if (nil? form)
            forms
            (recur (conj forms form)))))))


(doseq [form (read-forms-from-string clojure-src)]
  (println form)
  (eval form))




  )

        


(defn semantic-scholar [title]
  (e/with-firefox-headless driver
    (e/go driver (format #_"https://scholar.google.com/scholar?q=%s" "https://www.semanticscholar.org/search?q=%s&sort=relevance" title))
    (e/set-window-size driver {:width 1920 :height 1080})
    (e/wait 5)
    #_(e/get-element-text driver {:tag :body})
    (e/query-all driver {:css "div.cl-paper-row"})
    #_(e/wait-visible driver {:tag :div :class "cl-paper-row"})
    #_(e/get-element-text driver {:tag :div :class "cl-paper-row"})))

(comment
  (semantic-scholar "Attention+is+All+You+Need")

(json/read-value
 (:body
  (http/post "https://api.semanticscholar.org/graph/v1/paper/batch" {:body (json/write-value-as-string {:ids ["649def34f8be52c8b66281af98ae884c09aef38b" "ARXIV:2106.15928" "Attention is all you need"]
                                                                                                        :fields "referenceCount,citationCount,title"})})))

;; bulk search in clojure
(http/post "https://api.semanticscholar.org/graph/v1/paper/search/bulk" {:body (json/write-value-as-string {:query "covid vaccination"
                                                                                                            :fields "paperId,title,year,authors,fieldsOfStudy"
                                                                                                            :limit 10
                                                                                                            :publicationTypes "JournalArticle,Review"
                                                                                                            :fieldsOfStudy "Medicine,Public Health"
                                                                                                            :year "2020-2023"})})

(http/get "https://api.semanticscholar.org/graph/v1/paper/search?query=halloween&limit=3")

"http://export.arxiv.org/api/query?search_query=cat:cs.AI+AND+abs:\"identifiability\"&start=0&sortBy=submittedDate&max_results=10"

(parse-url (format "http://export.arxiv.org/api/query?search_query=%s&max_results=10" "attention+is+all+you+need") 
                    #_(str (- (.getTime (java.util.Date.))
                              (* 1000 60 60 24))))


(->
 (parse-url (format "http://export.arxiv.org/api/query?search_query=abs:\"identifiability\"+AND+abs:\"causal\"&start=0&sortBy=submittedDate&max_results=10"
                    0
                    #_(str (- (.getTime (java.util.Date.))
                              (* 1000 60 60 24)))))
 :feed :entries)


  )
