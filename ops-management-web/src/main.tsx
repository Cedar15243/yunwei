import { createRoot } from "react-dom/client";
import { createClient } from "@supabase/supabase-js";
import { App } from "./App";
import { createManagementApi } from "./api/management-api";
import "./styles/tokens.css";

const supabaseUrl = import.meta.env.VITE_SUPABASE_URL as string | undefined;
const supabaseAnonKey = import.meta.env.VITE_SUPABASE_ANON_KEY as string | undefined;
const apiBaseUrl = import.meta.env.VITE_OPS_API_BASE_URL as string | undefined;
const supabase = supabaseUrl && supabaseAnonKey ? createClient(supabaseUrl, supabaseAnonKey) : null;
const api = supabase && apiBaseUrl ? createManagementApi(apiBaseUrl, async () => (await supabase.auth.getSession()).data.session?.access_token ?? null) : undefined;
const auth = supabase ? {
  restoreSession: async () => Boolean((await supabase.auth.getSession()).data.session),
  signIn: async (email: string, password: string) => {
    const { error } = await supabase.auth.signInWithPassword({ email, password });
    if (error) throw new Error(error.message);
  },
} : undefined;

createRoot(document.getElementById("root")!).render(<App api={api} auth={auth} />);
