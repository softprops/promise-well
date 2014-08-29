# promise well

> kind of like a wishing well but for kepted promises

## usage

( wip )

promisewell defines a simple cache interface for capped (limited in number of items) and lru (least recently used) caches for Scala std library Futures.

Let's say you were building a lookup cache for reverse geocoding cities. It may be prudent to store all this information in memory and efficient to look this memoize lookups for commony requested cities.

```scala
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import promisewell.Cache

val well = Cache.lru[Coods, City](20, 10, 1 hour, 10 minutes)

// lookup a city directly in the cache
val cityOpt: Option[Future[City]] = well.get(latlon)

// lookup a city in the cache, falling back up updating the catch
// with an external lookup
val city: Future[City] = well(latlon, () => lookup(latlon))

// delete a city from the cache
val prevOpt: Option[City] = well.delete(latlon)

// remove all items in the cache
well.clear()
```


Doug Tangren (softprops) 2014
