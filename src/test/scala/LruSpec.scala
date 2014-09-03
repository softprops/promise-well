package promisewell

import org.scalatest.FunSpec
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
class LruSpec extends FunSpec {
  describe("Cache#lru") {
    it ("should cap items") {
      val evictions = new mutable.ArrayBuffer[Int]
      val well = Cache.lru[Int, Int](
        ttl = 1.second,
        ttidle = 1.second,
        maxCapacity = 2)
        .onEviction {
          case (k, _) =>
            evictions += k
        }
      well(1, () => Future.successful(1))
      well(2, () => Future.successful(2))
      well(3, () => Future.successful(3))
      assert(well.get(1) === None)
      assert(well.get(2).isDefined === true)
      assert(well.get(3).isDefined === true)
      assert(evictions.toList === List(1))
    }
    it ("should not cache failed futures") {
      val well = Cache.lru[Int, Int](1.second, 1.second)
      well(1, () => Future.failed(new Exception())).onComplete {
        case _ => assert(well.get(1).isDefined === false)
      }
    }
    it ("should honor ttl") {
      val evictions = new mutable.ArrayBuffer[Int]
      val well = Cache.lru[Int, Int](1.second, 1.second)
        .onEviction {
          case (k, _) =>
            evictions += k
        }
      well(1, () => Future.successful(1))
      assert(well.get(1).isDefined == true)
      Thread.sleep(2.seconds.toMillis)
      assert(well.get(1).isDefined === false)
      assert(evictions.toList === List(1))
    }
    it ("should honor ttidle") {
      val evictions = new mutable.ArrayBuffer[Int]
      val well = Cache.lru[Int, Int](2.seconds, 1.second)
        .onEviction {
          case (k, _) =>
            evictions += k
        }
      well(1, () => Future.successful(1))
      Thread.sleep(.5.seconds.toMillis)
      assert(well.get(1).isDefined === true)
      Thread.sleep(.5.seconds.toMillis)      
      assert(well.get(1).isDefined === true)
      Thread.sleep(5.seconds.toMillis)
      assert(well.get(1).isDefined === false)
      assert(evictions.toList === List(1))
    }
  }
}
