import { createRoot } from "react-dom/client";
import { createClient } from "@supabase/supabase-js";
import { App, type WebAuthState } from "./App";
import { createManagementApi } from "./api/management-api";
import "./styles/tokens.css";

const supabaseUrl = import.meta.env.VITE_SUPABASE_URL as string | undefined;
const supabaseAnonKey = import.meta.env.VITE_SUPABASE_ANON_KEY as string | undefined;
const apiBaseUrl = import.meta.env.VITE_OPS_API_BASE_URL as string | undefined;
const supabase = supabaseUrl && supabaseAnonKey ? createClient(supabaseUrl, supabaseAnonKey) : null;
const api = supabase && apiBaseUrl ? createManagementApi(apiBaseUrl, async () => (await supabase.auth.getSession()).data.session?.access_token ?? null) : undefined;
const auth = supabase ? createWebAuth(supabase) : undefined;

createRoot(document.getElementById("root")!).render(<App api={api} auth={auth} />);

function createWebAuth(client: NonNullable<typeof supabase>) {
  let state: WebAuthState = {
    authenticated: false,
    passwordRecovery: new URLSearchParams(window.location.hash.slice(1)).get("type") === "recovery",
  };
  const listeners = new Set<(nextState: WebAuthState) => void>();
  const publish = (nextState: WebAuthState) => {
    state = nextState;
    for (const listener of listeners) listener(state);
  };

  client.auth.onAuthStateChange((event, session) => {
    publish({
      authenticated: Boolean(session),
      passwordRecovery: event === "PASSWORD_RECOVERY"
        ? true
        : event === "SIGNED_OUT"
        ? false
        : state.passwordRecovery,
    });
  });

  return {
    restoreSession: async (): Promise<WebAuthState> => {
      const { data, error } = await client.auth.getSession();
      if (error) throw new Error(error.message);
      state = { ...state, authenticated: Boolean(data.session) };
      return state;
    },
    subscribe: (listener: (nextState: WebAuthState) => void) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    signIn: async (email: string, password: string) => {
      const { error } = await client.auth.signInWithPassword({ email, password });
      if (error) throw new Error(error.message);
    },
    completePasswordRecovery: async (password: string) => {
      const { error: updateError } = await client.auth.updateUser({ password });
      if (updateError) throw new Error(updateError.message);
      const { error: signOutError } = await client.auth.signOut({ scope: "local" });
      if (signOutError) throw new Error("密码已更新，但本地恢复会话清理失败，请关闭页面后重新登录。");
      window.history.replaceState(null, "", `${window.location.pathname}${window.location.search}`);
      publish({ authenticated: false, passwordRecovery: false });
    },
  };
}
