# Online Results

The launcher no longer performs any network requests of its own, and it does not
declare the `INTERNET` permission. Search covers apps, app shortcuts and
contacts, all of which are read from the device.

Web search actions still exist, but they hand the query to a browser via an
intent — the launcher itself never contacts the search engine. See
[Quick Actions](quickactions) for more information.
