package promisewell

import org.scalatest.FunSpec
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class CappedSpec extends FunSpec {
  describe("Cache#capped") {
    it ("should cap items") {
      val evictions = new mutable.ArrayBuffer[Int]
      val well = Cache.capped[Int, Int](maxCapacity = 2)
        .onEviction {
          case (k, v) =>
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
      val well = Cache.capped[Int, Int]()
      well(1, () => Future.failed(new Exception())).onComplete {
        case _ => assert(well.get(1).isDefined === false)
      }
    }
  }
}
