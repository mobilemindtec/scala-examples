
# The introduction to Reactive Programming you've been missing


Port RxJS code to ScalaJS/AirStream

### references

https://gist.github.com/staltz/868e7e9bc2a7b8c1f754



### JS Code

```javascript
	var refreshButton = document.querySelector('.refresh');
	var refreshClickStream = Rx.Observable.fromEvent(refreshButton, 'click');

	var closeButton1 = document.querySelector('.close1');
	var close1ClickStream = Rx.Observable.fromEvent(closeButton1, 'click');
	// and the same logic for close2 and close3

	var requestStream = refreshClickStream.startWith('startup click')
	  .map(function() {
	    var randomOffset = Math.floor(Math.random()*500);
	    return 'https://api.github.com/users?since=' + randomOffset;
	  });

	var responseStream = requestStream
	  .flatMap(function (requestUrl) {
	    return Rx.Observable.fromPromise($.ajax({url: requestUrl}));
	  });

	var suggestion1Stream = close1ClickStream.startWith('startup click')
	  .combineLatest(responseStream,             
	    function(click, listUsers) {
	      return listUsers[Math.floor(Math.random()*listUsers.length)];
	    }
	  )
	  .merge(
	    refreshClickStream.map(function(){ return null; })
	  )
	  .startWith(null);
	// and the same logic for suggestion2Stream and suggestion3Stream

	suggestion1Stream.subscribe(function(suggestion) {
	  if (suggestion === null) {
	    // hide the first suggestion DOM element
	  }
	  else {
	    // show the first suggestion DOM element
	    // and render the data
	  }
	});

```

## Dev

On sbt console, execute:

```shell
sbt> livereload
sbt> ~fastLinkJS
```

Open browser on http://localhost:3000
