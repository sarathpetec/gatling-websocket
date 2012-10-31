gatling-websocket
=================

This is a simple addon to add WebSocket support to
[Gatling](http://gatling-tool.org/).

Usage
=====

First create the jar:

    sbt package

... then copy `target/gatling-websocket-0.0.1.jar` to Gatling's `lib`
directory.

gatling-websocket uses the Jetty WebSocket API, so you also need to fetch the
latest stable version of
[Jetty 8](http://download.eclipse.org/jetty/stable-8/dist/) and copy
`lib/jetty-http_*.jar`, `lib/jetty_io_*.jar`, `lib/jetty_util_*.jar` and
`lib/jetty_websocket_*.jar` to Gatling's `lib` directory.

Finally, you need to add some settings to gatling.conf:

    gatling {
        ...
        websocket {
            maxClientThreads = 8
            bufferSize = 8192
            maxIdleTimeInMs = 300000
        }
        ...
    }

In your simulation, import the DSL:

    import com.giltgroupe.util.gatling.websocket.Predef._

You can now open a websocket:

    websocket("socket").open("ws://<url>/", "socket_open")

The parameter to `websocket` is the name of the session attribute used to
reference the socket later in the simulation. The second parameter to `open` is
optional and is the name used to record the amount of time the socket took to
open in the log; if omitted it defaults to the name of the session attribute.

You can send a text frame on an open socket:

    websocket("socket").sendMessage("test", "socket_send")

Again, the second parameter is optional and is the request name used to record
whether sending succeeded or not in the log (the time taken to send is usually
less than 1 millisecond).

Finally, you should close the socket at the end of the simulation:

    websocket("socket").close("socket_close")

The parameter is the optional request name again.

License
=======

Copyright 2012 Gilt Groupe, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
