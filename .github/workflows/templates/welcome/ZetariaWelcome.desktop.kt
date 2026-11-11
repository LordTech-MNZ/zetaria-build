package chat.simplex.common.views.onboarding

import androidx.compose.runtime.Composable
import chat.simplex.common.model.SharedPreference
import chat.simplex.common.model.User

// Escritorio: la bienvenida HTML del voto es parte de la app Android. Aquí se mantiene el
// diseño cósmico clásico para que el proyecto de escritorio siga compilando y usable.
@Composable
actual fun ZetariaWelcomeHost(user: User?, onboardingStage: SharedPreference<OnboardingStage>) {
  ZetariaWelcomeFallback(user, onboardingStage)
}
