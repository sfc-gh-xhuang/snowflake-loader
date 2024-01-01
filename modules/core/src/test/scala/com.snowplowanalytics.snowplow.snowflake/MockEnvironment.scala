/*
 * Copyright (c) 2014-present Snowplow Analytics Ltd. All rights reserved.
 *
 * This software is made available by Snowplow Analytics, Ltd.,
 * under the terms of the Snowplow Limited Use License Agreement, Version 1.0
 * located at https://docs.snowplow.io/limited-use-license-1.0
 * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
 * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
 */

package com.snowplowanalytics.snowplow.snowflake

import cats.effect.IO
import cats.effect.kernel.{Ref, Resource, Unique}
import org.http4s.client.Client
import fs2.Stream

import com.snowplowanalytics.snowplow.sources.{EventProcessingConfig, EventProcessor, SourceAndAck, TokenedEvents}
import com.snowplowanalytics.snowplow.sinks.Sink
import com.snowplowanalytics.snowplow.snowflake.processing.{Channel, Coldswap, TableManager}
import com.snowplowanalytics.snowplow.runtime.AppInfo

import scala.concurrent.duration.{DurationInt, FiniteDuration}

case class MockEnvironment(state: Ref[IO, Vector[MockEnvironment.Action]], environment: Environment[IO])

object MockEnvironment {

  sealed trait Action
  object Action {
    case object InitEventsTable extends Action
    case class Checkpointed(tokens: List[Unique.Token]) extends Action
    case class SentToBad(count: Int) extends Action
    case class AlterTableAddedColumns(columns: List[String]) extends Action
    case object ClosedChannel extends Action
    case object OpenedChannel extends Action
    case class WroteRowsToSnowflake(rowCount: Int) extends Action
    case class AddedGoodCountMetric(count: Int) extends Action
    case class AddedBadCountMetric(count: Int) extends Action
    case class SetLatencyMetric(millis: Long) extends Action
  }
  import Action._

  /**
   * Build a mock environment for testing
   *
   * @param inputs
   *   Input events to send into the environment.
   * @param channelResponses
   *   Responses we want the `Channel` to return when someone calls `write`
   * @return
   *   An environment and a Ref that records the actions make by the environment
   */
  def build(inputs: List[TokenedEvents], channelResponses: List[Channel.WriteResult]): Resource[IO, MockEnvironment] =
    for {
      state <- Resource.eval(Ref[IO].of(Vector.empty[Action]))
      channelResource <- Resource.eval(testChannel(state, channelResponses))
      channelColdswap <- Coldswap.make(channelResource)
    } yield {
      val env = Environment(
        appInfo      = appInfo,
        source       = testSourceAndAck(inputs, state),
        badSink      = testSink(state),
        httpClient   = testHttpClient,
        tableManager = testTableManager(state),
        channel      = channelColdswap,
        metrics      = testMetrics(state),
        batching = Config.Batching(
          maxBytes          = 16000000,
          maxDelay          = 10.seconds,
          uploadConcurrency = 1
        ),
        schemasToSkip = List.empty,
        badRowMaxSize = 1000000
      )
      MockEnvironment(state, env)
    }

  val appInfo = new AppInfo {
    def name        = "snowflake-loader-test"
    def version     = "0.0.0"
    def dockerAlias = "snowplow/snowflake-loader-test:0.0.0"
    def cloud       = "OnPrem"
  }

  private def testTableManager(state: Ref[IO, Vector[Action]]): TableManager[IO] = new TableManager[IO] {

    override def initializeEventsTable(): IO[Unit] =
      state.update(_ :+ InitEventsTable)

    def addColumns(columns: List[String]): IO[Unit] =
      state.update(_ :+ AlterTableAddedColumns(columns))
  }

  private def testSourceAndAck(inputs: List[TokenedEvents], state: Ref[IO, Vector[Action]]): SourceAndAck[IO] =
    new SourceAndAck[IO] {
      def stream(config: EventProcessingConfig, processor: EventProcessor[IO]): Stream[IO, Nothing] =
        Stream
          .emits(inputs)
          .through(processor)
          .chunks
          .evalMap { chunk =>
            state.update(_ :+ Checkpointed(chunk.toList))
          }
          .drain

      override def isHealthy(maxAllowedProcessingLatency: FiniteDuration): IO[SourceAndAck.HealthStatus] =
        IO.pure(SourceAndAck.Healthy)
    }

  private def testSink(ref: Ref[IO, Vector[Action]]): Sink[IO] = Sink[IO] { batch =>
    ref.update(_ :+ SentToBad(batch.asIterable.size))
  }

  private def testHttpClient: Client[IO] = Client[IO] { _ =>
    Resource.raiseError[IO, Nothing, Throwable](new RuntimeException("http failure"))
  }

  /**
   * Mocked implementation of a `Channel`
   *
   * @param actionRef
   *   Global Ref used to accumulate actions that happened
   * @param responses
   *   Responses that this mocked Channel should return each time someone calls `write`. If no
   *   responses given, then it will return with a successful response.
   */
  private def testChannel(
    actionRef: Ref[IO, Vector[Action]],
    responses: List[Channel.WriteResult]
  ): IO[Resource[IO, Channel[IO]]] =
    for {
      responseRef <- Ref[IO].of(responses)
    } yield {
      val make = actionRef.update(_ :+ OpenedChannel).as {
        new Channel[IO] {
          def write(rows: Iterable[Map[String, AnyRef]]): IO[Channel.WriteResult] =
            for {
              response <- responseRef.modify {
                            case head :: tail => (tail, head)
                            case Nil          => (Nil, Channel.WriteResult.WriteFailures(Nil))
                          }
              _ <- response match {
                     case Channel.WriteResult.WriteFailures(failures) =>
                       actionRef.update(_ :+ WroteRowsToSnowflake(rows.size - failures.size))
                     case Channel.WriteResult.ChannelIsInvalid =>
                       IO.unit
                   }
            } yield response
        }
      }

      Resource.make(make)(_ => actionRef.update(_ :+ ClosedChannel))
    }

  def testMetrics(ref: Ref[IO, Vector[Action]]): Metrics[IO] = new Metrics[IO] {
    def addBad(count: Int): IO[Unit] =
      ref.update(_ :+ AddedBadCountMetric(count))

    def addGood(count: Int): IO[Unit] =
      ref.update(_ :+ AddedGoodCountMetric(count))

    def setLatencyMillis(latencyMillis: Long): IO[Unit] =
      ref.update(_ :+ SetLatencyMetric(latencyMillis))

    def report: Stream[IO, Nothing] = Stream.never[IO]
  }
}
