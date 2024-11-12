# Simmis

Simmis is an interactive AGI system that tries to blend in to human environments as much as possible while doing useful background research and thinking ahead. It is trying to simulate what you need to know or decide next. The goal is to have both a principled organizational framework to structure problem solving and processes for humans and machines (i.e. a unified computational framework for intelligence) and a la carte abstractions that can be picked up from something as trivial as a group chat on a popular chat platform such as Telegram, WhatsApp, Slack, ... A concrete first example of this is a notion/wiki-like note taking system that is used by the conversational AI both as a memory and as a shared interface with humans.

At the moment Telegram is supported including automatic voice transcription. The system is also intended to support payments, e.g. through the Telegram API, and general resource awareness to guide its planning and computation. This code base is currently exploring design decisions and used in private betas with the Telegram bot interface. If you want to participate join our [Telegram group](https://t.me/+ANfiXz-khkpiOTg0) or open an issue on this repository.

# Design

The framework is based on communicating sequential processes (CSP) through [superv.async](https://github.com/replikativ/superv.async) and aims at decoupled asynchronous binding between parallel processes in [runtimes](./src/is/simm/runtimes/) and high-level [language](./src/is/simm/languages/) abstractions that send messages to these runtimes. The message flow follows [kabel](https://github.com/replikativ/kabel) which is a [ring](https://github.com/ring-clojure/ring)-like, continuous, bidirectional processing framework that allows separation of concerns between different middlewares. Middlewares can initiate events at any time and create multiple "return" messages. The system is therefore *not* purely reactive and goes beyond the standard lambda calculus employed in ring. A more functional perspective could be provided by FRP through [missionary](https://github.com/leonoel/missionary). 

The goal is to build a compositional framework of building blocks that can be easily integrated into JVM based backends. The backend uses HTMX by default to render implementations easy for backend developers, but a React frontend might make sense at some point. [libython-clj](https://github.com/clj-python/libpython-clj) is used for all data science, machine learning and generative AI related APIs and in general is useful for effects that can be invoked through stateless remote procedure calls (RPCs), while all state is managed by Clojure and [Datahike](https://github.com/replikativ/datahike) to keep a persistent, git-like memory model that is ideal for simulation environments. Future versions could leverage the GraalVM with [graalpython](https://github.com/oracle/graalpython) and node.js exposure to render the Clojure code easier to integrate into popular backends. The Clojure code itself including Datahike also is fairly portable and could be moved to frontends, e.g. browser, Android or maybe iOS through GraalVM native compilation.

## Installation

Make sure to define your credentials in `resources/config.edn`. Prompts are stored in `resources/prompts`.

## Usage

Just start a REPL and explore the [assistance](./src/is/simm/runtimes/assistance.clj) namespace.

## License

Copyright © 2024 Christian Weilbach

Distributed under the MIT license.
