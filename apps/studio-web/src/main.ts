import { mount } from 'svelte';
import App from './App.svelte';
import './app.css';
import { captureSource } from './lib/embed';
import { studio } from './lib/state.svelte';

// Avant tout rendu : le HTML d'origine sert de base à « Partager l'éditeur ».
captureSource();
studio.init();

export default mount(App, { target: document.getElementById('app')! });
