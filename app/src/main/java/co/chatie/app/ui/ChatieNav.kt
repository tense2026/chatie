package co.chatie.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import co.chatie.app.AppContainer
import co.chatie.app.baze.ModelCatalog
import co.chatie.app.context.ContextAssembler
import co.chatie.app.ui.chat.ChatScreen
import co.chatie.app.ui.chat.ChatSettingsScreen
import co.chatie.app.ui.chat.ChatViewModel
import co.chatie.app.ui.chat.ModelPickerScreen
import co.chatie.app.ui.conversations.ConversationsScreen
import co.chatie.app.ui.inspector.ContextInspectorScreen
import co.chatie.app.ui.projects.ProjectDetailScreen
import co.chatie.app.ui.projects.ProjectsScreen
import co.chatie.app.ui.settings.SettingsScreen

private object Routes {
    const val CONVERSATIONS = "conversations"
    const val CHAT = "chat/{id}"
    const val CHAT_SETTINGS = "chat/{id}/settings"
    const val INSPECTOR = "chat/{id}/context"
    const val MODEL_PICKER = "chat/{id}/model"
    const val PROJECTS = "projects"
    const val PROJECT = "projects/{id}"
    const val SETTINGS = "settings"

    fun chat(id: String) = "chat/$id"
    fun chatSettings(id: String) = "chat/$id/settings"
    fun inspector(id: String) = "chat/$id/context"
    fun modelPicker(id: String) = "chat/$id/model"
    fun project(id: String) = "projects/$id"
}

@Composable
fun ChatieNav(container: AppContainer) {
    val navController = rememberNavController()
    val catalog = remember(container) { ModelCatalog(container.repo, container.gateway) }

    NavHost(navController = navController, startDestination = Routes.CONVERSATIONS) {
        composable(Routes.CONVERSATIONS) {
            ConversationsScreen(
                repo = container.repo,
                onOpenConversation = { navController.navigate(Routes.chat(it)) },
                onNewConversation = {
                    val model = container.repo.getSetting(ContextAssembler.SETTING_DEFAULT_MODEL)
                        ?: ContextAssembler.DEFAULT_MODEL
                    val conversation = container.repo.createConversation(model = model)
                    navController.navigate(Routes.chat(conversation.id))
                },
                onOpenProjects = { navController.navigate(Routes.PROJECTS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(
            Routes.CHAT,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            val vm = chatViewModel(container, id)
            // Context switches live on other screens; re-read them on return.
            ChatScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenInspector = { navController.navigate(Routes.inspector(id)) },
                onOpenModelPicker = { navController.navigate(Routes.modelPicker(id)) },
                onOpenChatSettings = { navController.navigate(Routes.chatSettings(id)) },
            )
        }

        composable(
            Routes.CHAT_SETTINGS,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            ChatSettingsScreen(
                repo = container.repo,
                assembler = container.assembler,
                conversationId = id,
                onBack = { navController.popBackStack() },
                onDeleted = {
                    navController.popBackStack(Routes.CONVERSATIONS, inclusive = false)
                },
            )
        }

        composable(
            Routes.INSPECTOR,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            ContextInspectorScreen(
                repo = container.repo,
                assembler = container.assembler,
                conversationId = id,
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            Routes.MODEL_PICKER,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            val current = container.repo.getConversation(id)?.model.orEmpty()
            ModelPickerScreen(
                catalog = catalog,
                currentModel = current,
                onPick = { model ->
                    container.repo.updateConversation(id, model = model)
                    // Also become the default for new chats: picking a model is
                    // nearly always a preference, not a one-off.
                    container.repo.setSetting(ContextAssembler.SETTING_DEFAULT_MODEL, model)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.PROJECTS) {
            ProjectsScreen(
                repo = container.repo,
                onBack = { navController.popBackStack() },
                onOpenProject = { navController.navigate(Routes.project(it)) },
            )
        }

        composable(
            Routes.PROJECT,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            ProjectDetailScreen(
                repo = container.repo,
                projectId = id,
                onBack = { navController.popBackStack() },
                onOpenConversation = { navController.navigate(Routes.chat(it)) },
                onNewConversationInProject = { projectId ->
                    val model = container.repo.getProject(projectId)?.defaultModel
                        ?: container.repo.getSetting(ContextAssembler.SETTING_DEFAULT_MODEL)
                        ?: ContextAssembler.DEFAULT_MODEL
                    val conversation = container.repo.createConversation(
                        model = model,
                        projectId = projectId,
                    )
                    navController.navigate(Routes.chat(conversation.id))
                },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                repo = container.repo,
                keyStore = container.keyStore,
                gateway = container.gateway,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/** One ViewModel per conversation id, scoped to that nav entry. */
@Composable
private fun chatViewModel(container: AppContainer, conversationId: String): ChatViewModel =
    viewModel(
        key = "chat:$conversationId",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(
                repo = container.repo,
                assembler = container.assembler,
                gateway = container.gateway,
                conversationId = conversationId,
            ) as T
        },
    )
