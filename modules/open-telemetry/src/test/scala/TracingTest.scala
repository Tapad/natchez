import java.time.Duration
import java.util.concurrent.TimeUnit

import cats.Monad
import cats.data.Kleisli
import cats.effect.{ExitCode, IO, IOApp, Resource, Timer}
import cats.implicits._
import com.google.cloud.opentelemetry.trace.{TraceConfiguration, TraceExporter}
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.`export`.BatchSpanProcessor
import io.opentelemetry.trace.attributes.SemanticAttributes
import natchez.TraceValue._
import natchez.opentelemetry.OpenTelemetry
import natchez.{EntryPoint, Span, Trace}

import scala.concurrent.duration._

object TracingTest extends IOApp {

  // Configures OpenTelemetry tracing exporter for GCP
  val configureGCP = {
    val acquire = IO {
      val configuration = TraceConfiguration
        .builder()
        .setProjectId("tapad-paas-elias")
        .setDeadline(Duration.ofMillis(30000))
        .build()

      val exporter = TraceExporter.createWithConfiguration(configuration)
      val processor = BatchSpanProcessor.newBuilder(exporter).build
      OpenTelemetrySdk.getTracerManagement.addSpanProcessor(processor)
      processor
    }

    def release(processor: BatchSpanProcessor): IO[Unit] = IO {
      processor.forceFlush().join(10, TimeUnit.SECONDS)
      processor.shutdown().join(10, TimeUnit.SECONDS)
      ()
    }

    Resource.make(acquire)(release).void
  }

  def gcpEntrypoint: Resource[IO, EntryPoint[IO]] = {
    OpenTelemetry.entryPoint(
      instrumentationName = "com.elijordan.tracingtest",
      instrumentationVersion = Some("0,1.1"),
      conf = configureGCP
    )
  }

  def runF[F[_]: Trace: Timer: Monad]: F[Unit] = Trace[F].span("do-fake-work") {
    for {
      _ <- Trace[F].put(
        SemanticAttributes.HTTP_METHOD.getKey -> StringValue("POST"),
        SemanticAttributes.HTTP_URL.getKey -> StringValue("/foo/bar/1234"),
        SemanticAttributes.HTTP_ROUTE.getKey -> StringValue("/foo/bar/{id}"),
        SemanticAttributes.HTTP_STATUS_CODE.getKey -> NumberValue(301)
      )
      _ <- Trace[F].span("work-part-1") {
        Timer[F].sleep(5.seconds)
      }
      _ <- Trace[F].span("work-part-2") {
        Timer[F].sleep(5.seconds)
      }

    } yield ()
  }

  override def run(args: List[String]): IO[ExitCode] = {
    gcpEntrypoint.use { ep =>
      ep.root("root").use { span =>
        runF[Kleisli[IO, Span[IO], *]].run(span)
      }
    } as ExitCode.Success
  }
}
