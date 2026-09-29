async function loadAsciiArt() {
  const titleElement = document.getElementById('home-title-ascii');
  const plantElement = document.getElementById('home-plant-ascii');

  if (titleElement) {
    try {
      const titleResponse = await fetch('/ascii/title.txt');
      titleElement.textContent = await titleResponse.text();
    } catch (error) {
      console.error('Failed to load title ASCII art:', error);
    }
  }

  if (plantElement) {
    try {
      const plantResponse = await fetch('/ascii/plant.txt');
      plantElement.textContent = await plantResponse.text();
    } catch (error) {
      console.error('Failed to load plant ASCII art:', error);
    }
  }
}

export function initHome() {
  loadAsciiArt();
}
