package io.zenandroid.onlinego.ogs

import android.util.Log
import com.crashlytics.android.Crashlytics
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import io.zenandroid.onlinego.OnlineGoApplication
import io.zenandroid.onlinego.extensions.addToDisposable
import io.zenandroid.onlinego.gamelogic.Util
import io.zenandroid.onlinego.gamelogic.Util.isMyTurn
import io.zenandroid.onlinego.model.local.Clock
import io.zenandroid.onlinego.model.local.Game
import io.zenandroid.onlinego.model.ogs.GameData
import io.zenandroid.onlinego.model.ogs.OGSGame
import io.zenandroid.onlinego.model.ogs.Phase
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Created by alex on 08/11/2017.
 */
class ActiveGameRepository {

    private val activeDbGames = mutableMapOf<Long, Game>()
    private val gameConnections = mutableSetOf<Long>()
    private val connectedGameCache = hashMapOf<Long, Game>()

    private val myMoveCountSubject = BehaviorSubject.create<Int>()

    private val subscriptions = CompositeDisposable()

    private fun onNotification(game: OGSGame) {
        OGSServiceImpl.instance.fetchGame(game.id)
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.single())
                .map(Game.Companion::fromOGSGame)
                .retryWhen (this::retryIOException)
                .subscribe({
                    OnlineGoApplication.instance.db.gameDao().insertAll(listOf(it))
                }, { this.onError(it, "onNotification") })
                .addToDisposable(subscriptions)
    }

    val myMoveCountObservable: Observable<Int>
        @Synchronized get() = myMoveCountSubject.distinctUntilChanged()

    val myTurnGamesList: List<Game>
        get() = activeDbGames.values.filter(Util::isMyTurn).toList()

    internal fun subscribe() {
        OGSServiceImpl.instance.connectToNotifications()
                .subscribeOn(Schedulers.io())
                .subscribe(this::onNotification) { this.onError(it, "connectToNotifications") }
                .addToDisposable(subscriptions)
        OnlineGoApplication.instance.db.gameDao()
                .monitorActiveGames(OGSServiceImpl.instance.uiConfig?.user?.id)
                .subscribe(this::setActiveGames) { this.onError(it, "gameDao") }
                .addToDisposable(subscriptions)
    }

    internal fun unsubscribe() {
        subscriptions.clear()
        gameConnections.clear()
    }

    private fun connectToGame(baseGame: Game) {
        val game = baseGame.copy()
        synchronized(connectedGameCache) {
            connectedGameCache[game.id] = game
        }
        if(gameConnections.contains(game.id)) {
            return
        }
        gameConnections.add(game.id)

        val gameConnection = OGSServiceImpl.instance.connectToGame(game.id)
        gameConnection.addToDisposable(subscriptions)
        gameConnection.gameData
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.single())
                .subscribe (
                        { onGameData(game.id, it) },
                        { this.onError(it, "gameData") }
                )
                .addToDisposable(subscriptions)
        gameConnection.moves
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.single())
                .subscribe (
                        { onGameMove(game.id, it) },
                        { this.onError(it, "moves") }
                )
                .addToDisposable(subscriptions)
        gameConnection.clock
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.single())
                .subscribe (
                        { onGameClock(game.id, it) },
                        { this.onError(it, "clock") }
                )
                .addToDisposable(subscriptions)
        gameConnection.phase
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.single())
                .subscribe (
                        { onGamePhase(game.id, it) },
                        { this.onError(it, "phase") }
                )
                .addToDisposable(subscriptions)
        gameConnection.removedStones
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.single())
                .subscribe (
                        { onGameRemovedStones(game.id, it) },
                        { this.onError(it, "removedStones") }
                )
                .addToDisposable(subscriptions)
        gameConnection.undoRequested
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.single())
                .subscribe (
                        { onUndoRequested(game.id, it) },
                        { this.onError(it, "undoRequested") }
                )
                .addToDisposable(subscriptions)

    }

    private fun onGameRemovedStones(gameId: Long, stones: RemovedStones) {
        OnlineGoApplication.instance.db.gameDao().updateRemovedStones(gameId, stones.all_removed)
    }

    private fun onUndoRequested(gameId: Long, moveNo: Int) {
        OnlineGoApplication.instance.db.gameDao().updateUndoRequested(gameId, moveNo)
    }

    private fun onGamePhase(gameId: Long, newPhase: Phase) {
        OnlineGoApplication.instance.db.gameDao().updatePhase(gameId, newPhase)
    }

    private fun onGameClock(gameId: Long, clock: OGSClock) {
        OnlineGoApplication.instance.db.gameDao().updateClock(
                id = gameId,
                playerToMoveId = clock.current_player,
                clock = Clock.fromOGSClock(clock)
        )
    }

    private fun onGameData(gameId: Long, gameData: GameData) {
        OnlineGoApplication.instance.db.gameDao().updateGameData(
                id = gameId,
                outcome = gameData.outcome,
                phase = gameData.phase,
                playerToMoveId = gameData.clock.current_player,
                initialState = gameData.initial_state,
                whiteGoesFirst = gameData.initial_player == "white",
                moves = gameData.moves.map { mutableListOf(it[0].toInt(), it[1].toInt()) }.toMutableList(),
                removedStones = gameData.removed,
                whiteScore = gameData.score?.white,
                blackScore = gameData.score?.black,
                clock = Clock.fromOGSClock(gameData.clock),
                undoRequested = gameData.undo_requested
        )
    }

    private fun onGameMove(gameId: Long, move: Move ) {
        synchronized(connectedGameCache) {
            connectedGameCache[gameId]?.let { game ->
                game.moves?.let {
                    val newMoves = it.toMutableList().apply {
                        add(mutableListOf(move.move[0].toInt(), move.move[1].toInt()))
                    }
                    OnlineGoApplication.instance.db.gameDao().updateMoves(game.id, newMoves)
                }
            }
        }
    }

    @Synchronized
    private fun setActiveGames(games : List<Game>) {
        activeDbGames.clear()
        games.forEach {
            activeDbGames[it.id] = it
            connectToGame(it)
        }
        myMoveCountSubject.onNext(activeDbGames.values.count { isMyTurn(it) })
    }

    fun monitorGame(id: Long): Flowable<Game> {
        // TODO: Maybe check if the data is fresh enough to warrant skipping this call?
        OGSServiceImpl.instance
                .fetchGame(id)
                .map(Game.Companion::fromOGSGame)
                .map(::listOf)
                .retryWhen (this::retryIOException)
                .subscribe(OnlineGoApplication.instance.db.gameDao()::insertAll)
                { this.onError(it, "monitorGame") }
                .addToDisposable(subscriptions)

        return OnlineGoApplication.instance.db.gameDao().monitorGame(id)
                .doOnNext(this::connectToGame)
    }

    private fun retryIOException(it: Flowable<Throwable>) =
            it.map { it as? IOException ?: throw it }
                    .delay(15, TimeUnit.SECONDS)


    fun getGameSingle(id: Long): Single<Game> {
        return OnlineGoApplication.instance.db.gameDao().monitorGame(id).take(1).firstOrError()
    }

    fun fetchActiveGames(): Flowable<List<Game>> {
        OGSServiceImpl.instance
                .fetchActiveGames()
                .flattenAsObservable { it -> it }
                .map (Game.Companion::fromOGSGame)
                .toList()
                .retryWhen (this::retryIOException)
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.single())
                .subscribe(OnlineGoApplication.instance.db.gameDao()::insertAll)
                { this.onError(it, "fetchActiveGames") }
                .addToDisposable(subscriptions)
        return OnlineGoApplication.instance.db.gameDao().monitorActiveGames(OGSServiceImpl.instance.uiConfig?.user?.id)
    }

    fun fetchHistoricGames(): Flowable<List<Game>> {
        OGSServiceImpl.instance
                .fetchHistoricGames()
                .map { it.map(OGSGame::id) }
                .map { it - OnlineGoApplication.instance.db.gameDao().getHistoricGamesThatDontNeedUpdating(it) }
                .flattenAsObservable { it -> it }
                .flatMapSingle { OGSServiceImpl.instance.fetchGame(it) }
                .map (Game.Companion::fromOGSGame)
                .toList()
                .retryWhen (this::retryIOException)
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.single())
                .subscribe(OnlineGoApplication.instance.db.gameDao()::insertAll)
                { this.onError(it, "fetchHistoricGames") }
                .addToDisposable(subscriptions)
        return OnlineGoApplication.instance.db.gameDao().monitorHistoricGames(OGSServiceImpl.instance.uiConfig?.user?.id)
    }

    private fun onError(t: Throwable, request: String) {
        Crashlytics.logException(Exception(request, t))
        Log.e("ActiveGameRespository", t.message, t)
    }
}