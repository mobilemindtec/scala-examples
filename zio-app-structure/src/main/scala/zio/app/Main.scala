package zio.app

import zio.{Cause, Ref, Scope, Task, ZIO, ZIOAppDefault, ZLayer, Console}

import java.io.IOException


//https://softwaremill.com/structuring-zio-2-applications/


case class Connection(id: String)

class ConnectionPool(r: Ref[Vector[Connection]]):

  def obtain: Task[Connection] =
    r.modify {
      case h +: t => (h, t)
      case _ =>
        throw new IllegalStateException("Not connection available!")
    }
    .tap(c => ZIO.logInfo(s"Obtained connection: ${c.id}"))

  def release(c: Connection): Task[Unit] =
    r.modify(cs => ((), cs :+ c))
      .tap(_ => ZIO.logInfo(s"Released connection"))

object ConnectionPool:
  lazy val live: ZLayer[Any, Nothing, ConnectionPool] =
    ZLayer(
      Ref.make(
        Vector(
          Connection("conn1"),
          Connection("conn2"),
          Connection("conn3")
        )
      ).map(ConnectionPool(_))
    )

class DB(connectionPool: ConnectionPool):
  private def connection: ZIO[Scope, Throwable, Connection] =
      ZIO.acquireRelease(connectionPool.obtain)(c =>
        connectionPool
          .release(c)
          .catchAll(t => ZIO.logErrorCause("Exception when releasing a connection", Cause.fail(t)))
      )

  def transact[R, E, A](dbProgram: ZIO[Connection & R, E, A]): ZIO[R, E | Throwable, A] =
    ZIO.scoped {
      connection.flatMap { c =>
        dbProgram.provideSomeLayer(ZLayer.succeed(c))
      }
    }

object DB:
  lazy val live: ZLayer[ConnectionPool, Nothing, DB] =
    ZLayer.fromFunction(DB(_))

case class Car(brand: String,
               model: String,
               licensePlate: String)

class LicensePlateExistsError(message: String) extends Exception(message)

class CarRepository():
  def exists(licensePlate: String): ZIO[Connection, Nothing, Boolean] =
    ZIO
      .service[Connection]
      .map(_ => licensePlate.startsWith("WN"))
      .tap(_ => ZIO.logInfo(s"Checking if exists: $licensePlate"))

  def insert(car: Car): ZIO[Connection, Nothing, Unit] =
    ZIO
      .service[Connection]
      .map(_ => ())
      .tap(_ => ZIO.logInfo(s"Inserting car: $car"))

object CarRepository:
  lazy val live: ZLayer[Any, Nothing, CarRepository] =
    ZLayer.succeed(CarRepository())

class CarService(carRepository: CarRepository, db: DB):
  def register(car: Car): ZIO[Any, Throwable | LicensePlateExistsError, Unit] =
    db.transact {
      carRepository.exists(car.licensePlate).flatMap {
        case true => ZIO.fail(LicensePlateExistsError(car.licensePlate))
        case false => carRepository.insert(car)
      }
    }

object CarService:
  lazy val live: ZLayer[CarRepository & DB, Nothing, CarService] =
    ZLayer.fromFunction(CarService(_, _) )

class CarApi(carService: CarService):
  def register(input: String): ZIO[Any, Nothing, String] =
    input.split(" ", 3).toList match
      case List(f1, f2, f3) =>
        val car = Car(f1, f2, f3)
        carService.register(car)
          .as("OK. Car registered").catchAll {
            case _: LicensePlateExistsError =>
              ZIO.logError(s"Duplicate register: $car")
              .as("Bad request: duplicate")
            case t =>
              ZIO.logErrorCause(s"Cannot register: $car", Cause.fail(t))
                .as("Internal server error")
          }
      case _ => ZIO.logError(s"Bad request: $input")
                  .as("Bad request")

object CarApi:
  lazy val live: ZLayer[CarService, Any, CarApi] =
    ZLayer.fromFunction(CarApi(_))

object  Main extends  ZIOAppDefault:
  override def run: ZIO[Scope, Any, Any] =
    def program(api: CarApi): ZIO[Any, IOException, Unit] =
      for
        _ <- api.register("Toyota Corolla WE98765")
          .flatMap(Console.printLine(_))
        _ <- api.register("VW Golf WN12345")
          .flatMap(Console.printLine(_))
        _ <- api.register("Tesla")
          .flatMap(Console.printLine(_))
      yield ()

    ZLayer
      .make[CarApi](
        CarApi.live,
        CarService.live,
        CarRepository.live,
        DB.live,
        ConnectionPool.live
      )
      .build
      .map(_.get[CarApi])
      .flatMap(program)