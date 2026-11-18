import { useEffect, useState } from 'react';
import AdminPage from './pages/AdminPage';
import UserPage from './pages/UserPage';

type Route = 'user' | 'admin';

function pathToRoute(pathname: string): Route {
  return pathname.startsWith('/admin') ? 'admin' : 'user';
}

export default function App() {
  const [route, setRoute] = useState<Route>(() => pathToRoute(window.location.pathname));

  useEffect(() => {
    const onPop = () => setRoute(pathToRoute(window.location.pathname));
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
  }, []);

  function navigate(to: Route) {
    const path = to === 'admin' ? '/admin' : '/';
    if (window.location.pathname !== path) {
      window.history.pushState({}, '', path);
    }
    setRoute(to);
  }

  return (
    <div className="shell">
      <nav className="topnav" aria-label="主导航">
        <a
          href="/"
          className={`nav-link ${route === 'user' ? 'active' : ''}`}
          onClick={(e) => {
            e.preventDefault();
            navigate('user');
          }}
        >
          闪购
        </a>
        <a
          href="/admin"
          className={`nav-link ${route === 'admin' ? 'active' : ''}`}
          onClick={(e) => {
            e.preventDefault();
            navigate('admin');
          }}
        >
          管理
        </a>
      </nav>
      <main>{route === 'admin' ? <AdminPage /> : <UserPage />}</main>
    </div>
  );
}
