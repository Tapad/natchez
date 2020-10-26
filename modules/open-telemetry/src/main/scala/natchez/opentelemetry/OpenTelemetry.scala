package natchez.opentelemetry

import cats.effect.{Resource, Sync}
import cats.syntax.all._
import io.grpc.Context
import io.opentelemetry.trace.{Tracer, Span => OtSpan}
import io.opentelemetry.{OpenTelemetry => JOpenTelemetry}
import natchez.{EntryPoint, Kernel, Span}

object OpenTelemetry {
  def entryPoint[F[_]: Sync](instrumentationName: String,
                       instrumentationVersion: Option[String] = None,
                       conf: Resource[F, Unit]): Resource[F, EntryPoint[F]] = {
    val tracer = JOpenTelemetry.getTracer(instrumentationName, instrumentationVersion.getOrElse(""))
    conf.as(new OpenTelemetryEntryPoint[F](tracer))
  }

  private class OpenTelemetryEntryPoint[F[_]: Sync](tracer: Tracer)
      extends EntryPoint[F] {

    override def root(name: String): Resource[F, Span[F]] = {
      val acquire = Sync[F].delay(tracer.spanBuilder(name).startSpan())
      def release(span: OtSpan): F[Unit] = Sync[F].delay(span.end())
      Resource.make(acquire)(release).map(new OpenTelemetrySpan(tracer, _))
    }

    override def continue(name: String, kernel: Kernel): Resource[F, Span[F]] = {
      val acquire = Sync[F].delay {
        val propagator = JOpenTelemetry.getPropagators.getTextMapPropagator
        def getter(map: Map[String, String], key: String): String =
          map.get(key).orNull
        val context =
          propagator.extract(Context.current(), kernel.toHeaders, getter)

        tracer
          .spanBuilder(name)
          .setParent(context)
          .startSpan()
      }

      def release(span: OtSpan): F[Unit] = Sync[F].delay(span.end())

      Resource.make(acquire)(release)
    }.map(new OpenTelemetrySpan[F](tracer, _))

    override def continueOrElseRoot(name: String,
                                    kernel: Kernel): Resource[F, Span[F]] =
      continue(name, kernel)
  }


}
