package io.zenandroid.onlinego.mygames

import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.SimpleItemAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import io.zenandroid.onlinego.OnlineGoApplication
import io.zenandroid.onlinego.R
import io.zenandroid.onlinego.main.MainActivity
import io.zenandroid.onlinego.model.local.Game
import io.zenandroid.onlinego.reusable.ActiveGameItem
import io.zenandroid.onlinego.reusable.FinishedGameItem
import kotlinx.android.synthetic.main.fragment_mygames.*

/**
 * Created by alex on 05/11/2017.
 */
class MyGamesFragment : Fragment(), MyGamesContract.View {
    private val groupAdapter = GameListGroupAdapter()

    private lateinit var presenter: MyGamesContract.Presenter
    private var analytics = OnlineGoApplication.instance.analytics

    private var lastReportedGameCount = -1

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            inflater.inflate(R.layout.fragment_mygames, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        gamesRecycler.layoutManager = LinearLayoutManager(context)
        (gamesRecycler.itemAnimator as SimpleItemAnimator).supportsChangeAnimations = false
        gamesRecycler.adapter = groupAdapter
        groupAdapter.setOnItemClickListener { item, _ ->
            when(item) {
                is ActiveGameItem -> presenter.onGameSelected(item.game)
                is FinishedGameItem -> presenter.onGameSelected(item.game)
            }
        }

        presenter = MyGamesPresenter(this, (activity as MainActivity).activeGameRepository)
    }

    override fun navigateToGameScreen(game: Game) {
        analytics.logEvent("clicked_game", Bundle().apply {
            putLong("GAME_ID", game.id)
            putBoolean("ACTIVE_GAME", game.ended == null)
        })
        (activity as MainActivity).navigateToGameScreen(game)
    }

    override fun onResume() {
        super.onResume()
        analytics.setCurrentScreen(activity!!, javaClass.simpleName, null)
        (activity as? AppCompatActivity)?.supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(false)
        }

        (activity as? MainActivity)?.apply {
            setLogoVisible(true)
            setChatButtonVisible(false)
        }
        presenter.subscribe()
    }

    override fun setHistoricGames(games: List<Game>) {
        groupAdapter.setHistoricGames(games)
    }

    override fun onPause() {
        super.onPause()
        presenter.unsubscribe()
    }

    override fun setGames(games: List<Game>) {
        if(lastReportedGameCount != games.size) {
            analytics.logEvent("active_games", Bundle().apply { putInt("GAME_COUNT", games.size) })
            lastReportedGameCount = games.size
        }
        groupAdapter.setGames(games)
    }

    override fun setLoading(loading: Boolean) {
        (activity as? MainActivity)?.loading = loading
    }

}